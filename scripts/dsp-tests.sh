#!/bin/bash

# Cross-VE DSP test: as the provider VE's newest onboarded participant, offers an asset under
# a credential-constrained use-policy; as the consumer VE's newest participant, requests the
# provider's catalog, negotiates a contract and establishes an HttpData-PULL transfer process
# through the participants' siglet data planes. Succeeds when the negotiation reaches
# FINALIZED and the transfer process reaches STARTED, and prints the agreement and
# transfer-process ids.
#
# The test is driven from the host with curl: every management-plane request traverses the
# platform's real edge — Traefik (HTTPRoute with URL rewrite) → clearglass (JWT forward-auth
# against the route→scope map) → backend — so it also covers the gateway auth chain, which
# an in-cluster probe pod would bypass.
#
# Auth: kubectl mints a TokenRequest token for the seed-jobs ServiceAccount with audience
# https://kubernetes.default.svc.cluster.local — equivalent to the projected token a pod
# would mount — and jwtlet exchanges it (RFC 8693) at <gateway>/api/auth/token for a scoped
# access token. jwtlet expands the role scopes into the API-grammar scopes clearglass
# requires (admin → "management-api:admin identity-api:admin issuer-admin-api:admin",
# cfm-read → "provision-manager-api:read tenant-manager-api:read"); bare role scopes would
# NOT pass clearglass, which matches non-grammar scopes exactly.
#
# Gateway paths (prefix rewrites done by the platform's HTTPRoutes):
#   /api/auth       → jwtlet /                    (token exchange, no auth middleware)
#   /api/management → controlplane /api/mgmt      (management API)
#   /api/tm         → tenant-manager /api/v1alpha1
#
# The DSP counterparty address stays the in-cluster URL (controlplane.edc-v.svc.<domain>:8082)
# — it is dereferenced by the consumer VE's controlplane over the connect-ves.sh routing, not
# from the host (the DSP port has no gateway route).
#
# Prerequisites: both VEs installed (install-ve.sh), a participant onboarded in each
# (onboard-participant.sh) and the VEs connected (connect-ves.sh). Requires kubectl, curl, jq.
#
# Usage:
#   ./scripts/dsp-tests.sh [--pc <cluster>] [--pd <domain>] [--pu <gateway-url>]
#                          [--cc <cluster>] [--cd <domain>] [--cu <gateway-url>]
#                          [--asset <id>] [-h|--help]

set -euo pipefail

PROV_CLUSTER=ve1; PROV_DOMAIN=ve1.local; PROV_URL=http://ve1.localhost
CONS_CLUSTER=ve2; CONS_DOMAIN=ve2.local; CONS_URL=http://ve2.localhost:8081
# Default derived from the provider participant context after discovery: ids are unique across
# participant contexts in the store, so a fixed id would 409 against an older participant's
# asset without ever appearing in this participant's catalog
ASSET_ID=""
NAMESPACE=edc-v
AUDIENCE=edcv

usage() {
  cat <<EOF
Usage: $(basename "$0") [--pc <cluster>] [--pd <domain>] [--pu <gateway-url>]
                        [--cc <cluster>] [--cd <domain>] [--cu <gateway-url>]
                        [--asset <id>] [-h|--help]

Options (defaults match the dual-VE install convention):
  --pc/--pd/--pu  provider cluster / DNS domain / gateway base URL
                  (default: ve1 / ve1.local / http://ve1.localhost)
  --cc/--cd/--cu  consumer cluster / DNS domain / gateway base URL
                  (default: ve2 / ve2.local / http://ve2.localhost:8081)
  --asset         asset id to offer and negotiate (default: demo-asset-<provider-ctx-prefix>)
  -h, --help      show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pc|--pd|--pu|--cc|--cd|--cu|--asset)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        --pc) PROV_CLUSTER="$2" ;; --pd) PROV_DOMAIN="$2" ;; --pu) PROV_URL="$2" ;;
        --cc) CONS_CLUSTER="$2" ;; --cd) CONS_DOMAIN="$2" ;; --cu) CONS_URL="$2" ;;
        --asset) ASSET_ID="$2" ;;
      esac
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

GEN_DIR=$(mktemp -d)
kubeconfig() { echo "$HOME/.kube/$1.config"; }

base_of() { # <cluster> -> gateway base URL
  if [[ "$1" == "$PROV_CLUSTER" ]]; then echo "$PROV_URL"; else echo "$CONS_URL"; fi
}
sa_of() { # <cluster> -> cached SA subject token
  if [[ "$1" == "$PROV_CLUSTER" ]]; then echo "$PROV_SA"; else echo "$CONS_SA"; fi
}

# The subject tokens are minted once up front (valid 1h); the scoped access tokens are
# exchanged per request below — mgmt() runs in command substitutions, so a lazily filled
# cache would not survive the subshell anyway, and per-request exchange sidesteps any
# access-token TTL during the polling loops.
echo ">> Minting seed-jobs subject tokens"
PROV_SA=$(kubectl --kubeconfig "$(kubeconfig "$PROV_CLUSTER")" create token seed-jobs -n "$NAMESPACE" \
  --audience=https://kubernetes.default.svc.cluster.local --duration=3600s)
CONS_SA=$(kubectl --kubeconfig "$(kubeconfig "$CONS_CLUSTER")" create token seed-jobs -n "$NAMESPACE" \
  --audience=https://kubernetes.default.svc.cluster.local --duration=3600s)

xtoken() { # <cluster> <scope> -> access token on stdout
  local base sa tok i
  base=$(base_of "$1"); sa=$(sa_of "$1")
  for i in 1 2 3 4 5; do
    tok=$(curl -s -m 10 -X POST "$base/api/auth/token" \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
      --data-urlencode "subject_token=$sa" \
      --data-urlencode 'subject_token_type=urn:ietf:params:oauth:token-type:jwt' \
      --data-urlencode 'resource=issuer' --data-urlencode "scope=$2" \
      --data-urlencode "audience=$AUDIENCE" | jq -r '.access_token // empty')
    [[ -n "$tok" ]] && { printf '%s' "$tok"; return 0; }
    sleep 3
  done
  echo "ERROR: $1: token exchange failed at $base/api/auth/token (scope $2)" >&2
  return 1
}

# mgmt <cluster> <method> <path> [json-file] -> body on stdout, fails on HTTP >= 400 (409 ok)
mgmt() {
  local base tok out status body
  base=$(base_of "$1")
  tok=$(xtoken "$1" admin)
  if [[ $# -ge 4 ]]; then
    out=$(curl -s -m 30 -w '\n%{http_code}' -X "$2" "$base/api/management/v5beta$3" \
      -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' --data @"$4")
  else
    out=$(curl -s -m 30 -w '\n%{http_code}' -X "$2" "$base/api/management/v5beta$3" \
      -H "Authorization: Bearer $tok")
  fi
  status=$(printf '%s' "$out" | tail -1)
  body=$(printf '%s' "$out" | sed '$d')
  if [[ "$status" == 409 ]]; then
    echo ">> $1 $2 $3: already present (409)" >&2
  elif [[ "$status" -ge 400 ]]; then
    echo "ERROR: $1 $2 $3 -> HTTP $status: $body" >&2
    return 1
  fi
  printf '%s' "$body"
}

# ---- discover the participant contexts ------------------------------------------------------
# Newest participant (last in the list): participants onboarded by the current app version carry
# a registered data plane (transfer-type mappings via the cfm.dataplane VPA), older ones may not
PROV_CTX=$(mgmt "$PROV_CLUSTER" GET /participants | jq -r '.[-1]["@id"]')
PROV_DID=$(mgmt "$PROV_CLUSTER" GET /participants | jq -r '.[-1].identity')
CONS_CTX=$(mgmt "$CONS_CLUSTER" GET /participants | jq -r '.[-1]["@id"]')
CONS_DID=$(mgmt "$CONS_CLUSTER" GET /participants | jq -r '.[-1].identity')
for v in PROV_CTX PROV_DID CONS_CTX CONS_DID; do
  [[ -n "${!v}" && "${!v}" != null ]] || { echo "ERROR: participant discovery failed ($v empty) — are participants onboarded in both VEs?" >&2; exit 1; }
done
PROV_DSP="http://controlplane.${NAMESPACE}.svc.${PROV_DOMAIN}:8082/api/dsp/${PROV_CTX}/cx-neptune"
# Scope the demo object ids by participant context (ids are store-unique across contexts)
ASSET_ID="${ASSET_ID:-demo-asset-${PROV_CTX:0:8}}"
POLICY_ID="policy-credentials-${PROV_CTX:0:8}"
echo ">> Provider:  $PROV_DID ($PROV_CTX)"
echo ">> Consumer:  $CONS_DID ($CONS_CTX)"

# The BusinessPartnerNumber policy constraint pins the consumer's BPN; read it from the
# consumer VE's tenant manager (the cfm.issuer VPA properties of the participant profile carry
# the bpn the credentials were issued for)
CFM_TOK=$(xtoken "$CONS_CLUSTER" cfm-read)
TM="$CONS_URL/api/tm"
CONS_BPN=$(for t in $(curl -s -m 10 -H "Authorization: Bearer $CFM_TOK" "$TM/tenants" | jq -r '.[].id'); do
  curl -s -m 10 -H "Authorization: Bearer $CFM_TOK" "$TM/tenants/$t/participant-profiles" \
    | jq -r --arg did "$CONS_DID" '.[] | select(.identifier==$did) | .vpas[]? | select(.type=="cfm.issuer") | .properties.bpn // empty'
done | head -1)
[[ -n "$CONS_BPN" ]] || { echo "ERROR: could not determine the consumer's BPN from the tenant manager" >&2; exit 1; }
echo ">> Consumer BPN: $CONS_BPN"

# ---- provider: asset + credential-constrained policy + contract definition ------------------
cat > "$GEN_DIR/asset.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2"],
  "@type": "Asset",
  "@id": "${ASSET_ID}",
  "properties": {"name": "Cross-VE DSP demo asset"},
  "dataAddress": {"@type": "DataAddress", "type": "HttpData", "baseUrl": "https://jsonplaceholder.typicode.com/todos/1"}
}
EOF
# The use-permission requires all three credentials issued at onboarding, matching the CEL
# expressions seeded by the Catena-X profile (leftOperand IRIs under …catenax/2025/9/policy/):
#   Membership              — MembershipCredential with memberOf == 'Catena-X' (rightOperand
#                             is ignored by the CEL; 'active' is the CX convention)
#   FrameworkAgreement      — DataExchangeGovernanceCredential whose contractVersion matches
#                             the right operand ('DataExchangeGovernance:' + contractVersion;
#                             the Onboarding API issues contractVersion 1.0.0)
#   BusinessPartnerNumber   — BpnCredential with bpn == the consumer's BPN
# The same policy definition serves as BOTH access policy (catalog visibility) and contract
# policy (negotiation + transfer) via the contract definition below.
cat > "$GEN_DIR/policy.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2", "http://www.w3.org/ns/odrl.jsonld"],
  "@type": "PolicyDefinition",
  "@id": "${POLICY_ID}",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@type": "Set",
    "permission": [{
      "action": "use",
      "constraint": [
        {"@type": "Constraint", "leftOperand": "https://w3id.org/catenax/2025/9/policy/Membership", "operator": "eq", "rightOperand": "active"},
        {"@type": "Constraint", "leftOperand": "https://w3id.org/catenax/2025/9/policy/FrameworkAgreement", "operator": "eq", "rightOperand": "DataExchangeGovernance:1.0.0"},
        {"@type": "Constraint", "leftOperand": "https://w3id.org/catenax/2025/9/policy/BusinessPartnerNumber", "operator": "eq", "rightOperand": "${CONS_BPN}"}
      ]
    }]
  }
}
EOF
cat > "$GEN_DIR/contractdef.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2"],
  "@type": "ContractDefinition",
  "@id": "cd-${ASSET_ID}",
  "accessPolicyId": "${POLICY_ID}",
  "contractPolicyId": "${POLICY_ID}",
  "assetsSelector": []
}
EOF
echo ">> Seeding provider offer"
mgmt "$PROV_CLUSTER" POST "/participants/${PROV_CTX}/assets" "$GEN_DIR/asset.json" >/dev/null
# Upsert: PUT updates an existing definition's content (a tolerated 409 on POST would silently
# keep stale policy content); fall back to POST for first-time creation
mgmt "$PROV_CLUSTER" PUT "/participants/${PROV_CTX}/policydefinitions/${POLICY_ID}" "$GEN_DIR/policy.json" >/dev/null 2>&1 \
  || mgmt "$PROV_CLUSTER" POST "/participants/${PROV_CTX}/policydefinitions" "$GEN_DIR/policy.json" >/dev/null
mgmt "$PROV_CLUSTER" POST "/participants/${PROV_CTX}/contractdefinitions" "$GEN_DIR/contractdef.json" >/dev/null

# ---- consumer: catalog request --------------------------------------------------------------
cat > "$GEN_DIR/catalog-request.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2"],
  "@type": "CatalogRequest",
  "counterPartyAddress": "${PROV_DSP}",
  "counterPartyId": "${PROV_DID}",
  "protocol": "cx-neptune"
}
EOF
echo ">> Requesting catalog from consumer side"
CATALOG=$(mgmt "$CONS_CLUSTER" POST "/participants/${CONS_CTX}/catalog/request" "$GEN_DIR/catalog-request.json")
OFFER=$(printf '%s' "$CATALOG" | jq --arg a "$ASSET_ID" \
  '.dataset | if type=="array" then . else [.] end | .[] | select(.["@id"]==$a) | .hasPolicy | if type=="array" then .[0] else . end')
OFFER_ID=$(printf '%s' "$OFFER" | jq -r '.["@id"] // empty')
[[ -n "$OFFER_ID" ]] || { echo "ERROR: asset '$ASSET_ID' not found in catalog (access policy unsatisfied, or not seeded): $CATALOG" >&2; exit 1; }
echo ">> Catalog contains '$ASSET_ID', offer: $OFFER_ID"

# ---- consumer: contract negotiation ---------------------------------------------------------
# The request must reproduce the provider's offer policy EXACTLY (including the credential
# constraints), so mirror the offer from the catalog response verbatim — under the catalog's
# own JSON-LD contexts, so compacted terms expand back to the same IRIs.
CATALOG_CTX=$(printf '%s' "$CATALOG" | jq '.["@context"] | if type=="array" then . else [.] end')
jq -n --argjson ctx "$CATALOG_CTX" --argjson offer "$OFFER" \
      --arg dsp "$PROV_DSP" --arg assigner "$PROV_DID" --arg target "$ASSET_ID" '{
  "@context": (["https://w3id.org/edc/connector/management/v2"] + $ctx),
  "@type": "ContractRequest",
  "counterPartyAddress": $dsp,
  "protocol": "cx-neptune",
  "policy": ($offer + {"assigner": $assigner, "target": $target})
}' > "$GEN_DIR/negotiation.json"
NEG_ID=$(mgmt "$CONS_CLUSTER" POST "/participants/${CONS_CTX}/contractnegotiations" "$GEN_DIR/negotiation.json" | jq -r '.["@id"]')
echo ">> Negotiation started: $NEG_ID"

AGREEMENT=""
for i in $(seq 1 40); do
  NEG=$(mgmt "$CONS_CLUSTER" GET "/participants/${CONS_CTX}/contractnegotiations/${NEG_ID}")
  STATE=$(printf '%s' "$NEG" | jq -r .state)
  case "$STATE" in
    FINALIZED)
      AGREEMENT=$(printf '%s' "$NEG" | jq -r .contractAgreementId)
      echo ">> Negotiation FINALIZED — contract agreement: $AGREEMENT"
      break
      ;;
    TERMINATED)
      echo "ERROR: negotiation TERMINATED: $(printf '%s' "$NEG" | jq -r .errorDetail)" >&2
      exit 1
      ;;
    *) echo ">> negotiation state: $STATE"; sleep 3 ;;
  esac
done
[[ -n "$AGREEMENT" && "$AGREEMENT" != null ]] || { echo "ERROR: negotiation did not reach FINALIZED in time" >&2; exit 1; }

# ---- consumer: transfer process -------------------------------------------------------------
# HttpData-PULL: the provider's siglet data plane starts the flow and hands the data address to
# the consumer through the DSP TransferStartMessage; STARTED on the consumer side means both
# data planes are engaged. (The application-facing pull then goes through the consumer siglet's
# own API — outside this demo's scope.)
cat > "$GEN_DIR/transfer.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2"],
  "@type": "TransferRequest",
  "contractId": "${AGREEMENT}",
  "counterPartyAddress": "${PROV_DSP}",
  "protocol": "cx-neptune",
  "transferType": "HttpData-PULL"
}
EOF
TP_ID=$(mgmt "$CONS_CLUSTER" POST "/participants/${CONS_CTX}/transferprocesses" "$GEN_DIR/transfer.json" | jq -r '.["@id"]')
echo ">> Transfer process started: $TP_ID"

for i in $(seq 1 40); do
  TP=$(mgmt "$CONS_CLUSTER" GET "/participants/${CONS_CTX}/transferprocesses/${TP_ID}")
  STATE=$(printf '%s' "$TP" | jq -r .state)
  case "$STATE" in
    STARTED|COMPLETED)
      echo ">> Transfer process $STATE (type $(printf '%s' "$TP" | jq -r .transferType))"
      echo "Cross-VE DSP exchange OK: ${CONS_DID} negotiated '${ASSET_ID}' from ${PROV_DID} (agreement ${AGREEMENT}) and established transfer ${TP_ID}"
      exit 0
      ;;
    TERMINATED)
      echo "ERROR: transfer TERMINATED: $(printf '%s' "$TP" | jq -r .errorDetail)" >&2
      exit 1
      ;;
    *) echo ">> transfer state: $STATE"; sleep 3 ;;
  esac
done
echo "ERROR: transfer did not reach STARTED in time" >&2
exit 1
