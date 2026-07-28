#!/bin/bash

# Cross-VE DSP test: as the provider VE's newest onboarded participant, offers an asset under
# a credential-constrained use-policy; as the consumer VE's newest participant, requests the
# provider's catalog, negotiates a contract, establishes an HttpData-PULL transfer process
# through the participants' siglet data planes and downloads the payload. Succeeds when the
# negotiation reaches FINALIZED, the transfer process reaches STARTED and the endpoint from
# the transfer's EDR serves the payload; prints the agreement/transfer ids and the payload.
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
#   /api/siglet     → siglet /                    (data-plane token cache: EDR retrieval)
#
# The DSP counterparty address is the provider's gateway URL (http://<prov-host>/api/dsp/…) —
# the address the platform actually advertises to counterparties. It is dereferenced by the
# consumer VE's controlplane, which resolves the provider's gateway hostname through the peer
# DNS forwarding connect-ves.sh sets up (setup-did-dns.sh --peer).
#
# Prerequisites: both VEs installed (install-ve.sh), a participant onboarded in each
# (onboard-participant.sh) and the VEs connected (connect-ves.sh). Requires kubectl, curl, jq.
#
# Usage:
#   ./scripts/dsp-tests.sh [--pc <cluster>] [--pu <gateway-url>]
#                          [--cc <cluster>] [--cu <gateway-url>]
#                          [--asset <id>] [-h|--help]

set -euo pipefail

PROV_CLUSTER=ve1; PROV_URL=http://ve1.localhost
CONS_CLUSTER=ve2; CONS_URL=http://ve2.localhost:8081
# Default derived from the provider participant context after discovery: ids are unique across
# participant contexts in the store, so a fixed id would 409 against an older participant's
# asset without ever appearing in this participant's catalog
ASSET_ID=""
NAMESPACE=edc-v
AUDIENCE=edcv

usage() {
  cat <<EOF
Usage: $(basename "$0") [--pc <cluster>] [--pu <gateway-url>]
                        [--cc <cluster>] [--cu <gateway-url>]
                        [--asset <id>] [-h|--help]

Options (defaults match the dual-VE install convention):
  --pc/--pu  provider cluster / gateway base URL (default: ve1 / http://ve1.localhost)
  --cc/--cu  consumer cluster / gateway base URL (default: ve2 / http://ve2.localhost:8081)
  --asset    asset id to offer and negotiate (default: demo-asset-<provider-ctx-prefix>)
  -h, --help show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pc|--pu|--cc|--cu|--asset)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        --pc) PROV_CLUSTER="$2" ;; --pu) PROV_URL="$2" ;;
        --cc) CONS_CLUSTER="$2" ;; --cu) CONS_URL="$2" ;;
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

# mgmt <cluster> <method> <path> [json-file] -> body on stdout, fails on HTTP >= 400 (409 ok).
# 502/503/504 are retried: they mean Traefik could not reach (or complete against) the backend,
# so the request was not processed. The window is real — connect-ves.sh restarts the controlplanes
# for the trusted-issuer config, and the gateway's endpoint view can lag the rollout by a few
# seconds, so the first management call afterwards may hit a stale endpoint.
mgmt() {
  local base tok out status body attempt
  base=$(base_of "$1")
  tok=$(xtoken "$1" admin)
  for attempt in 1 2 3 4 5; do
    if [[ $# -ge 4 ]]; then
      out=$(curl -s -m 30 -w '\n%{http_code}' -X "$2" "$base/api/management/v5beta$3" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' --data @"$4")
    else
      out=$(curl -s -m 30 -w '\n%{http_code}' -X "$2" "$base/api/management/v5beta$3" \
        -H "Authorization: Bearer $tok")
    fi
    status=$(printf '%s' "$out" | tail -1)
    body=$(printf '%s' "$out" | sed '$d')
    case "$status" in
      502|503|504)
        echo ">> $1 $2 $3: HTTP $status (gateway/backend not settled), retry $attempt" >&2
        sleep 3
        ;;
      *) break ;;
    esac
  done
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
# The provider's advertised DSP endpoint (see header). Note the consumer's controlplane — not
# the host — dereferences this, so the URL must work from the consumer's pods: true for the
# default topology, where the provider's gateway listens on port 80 both in-cluster and on the
# host. A provider on a non-default host port would need the in-cluster port here instead.
PROV_DSP="${PROV_URL}/api/dsp/${PROV_CTX}/cx-neptune"
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
# HttpData-PULL: the provider's siglet data plane starts the flow and hands the data address
# (EDR: endpoint + provider-minted access token) to the consumer through the DSP
# TransferStartMessage; STARTED on the consumer side means both data planes are engaged and
# the consumer siglet caches the EDR for applications.
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

TP_STATE=""
for i in $(seq 1 40); do
  TP=$(mgmt "$CONS_CLUSTER" GET "/participants/${CONS_CTX}/transferprocesses/${TP_ID}")
  STATE=$(printf '%s' "$TP" | jq -r .state)
  case "$STATE" in
    STARTED|COMPLETED)
      TP_STATE=$STATE
      echo ">> Transfer process $STATE (type $(printf '%s' "$TP" | jq -r .transferType))"
      break
      ;;
    TERMINATED)
      echo "ERROR: transfer TERMINATED: $(printf '%s' "$TP" | jq -r .errorDetail)" >&2
      exit 1
      ;;
    *) echo ">> transfer state: $STATE"; sleep 3 ;;
  esac
done
[[ -n "$TP_STATE" ]] || { echo "ERROR: transfer did not reach STARTED in time" >&2; exit 1; }

# ---- consumer: data download ----------------------------------------------------------------
# The consumer siglet caches the transfer's EDR (with automatic renewal against the provider
# siglet) and serves it to applications at GET /tokens/{participant-context}/{transfer-id}.
# Retrieve it through the consumer gateway — the `read` role scope expands to siglet-api:read,
# which clearglass's /tokens/** rule accepts — then pull the payload from the EDR endpoint
# with the EDR token. NOTE the demo data source (jsonplaceholder) ignores the Authorization
# header, so the download proves EDR delivery end to end, not token enforcement at the source.
SIGLET_TOK=$(xtoken "$CONS_CLUSTER" read)
EDR=""
for i in $(seq 1 10); do
  EDR=$(curl -s -m 15 -H "Authorization: Bearer $SIGLET_TOK" \
    "$CONS_URL/api/siglet/tokens/${CONS_CTX}/${TP_ID}")
  [[ -n "$(printf '%s' "$EDR" | jq -r '.token // empty' 2>/dev/null)" ]] && break
  echo ">> EDR not cached yet, retrying"; sleep 3
done
EDR_ENDPOINT=$(printf '%s' "$EDR" | jq -r '.endpoint // empty')
EDR_TOKEN=$(printf '%s' "$EDR" | jq -r '.token // empty')
[[ -n "$EDR_ENDPOINT" && -n "$EDR_TOKEN" ]] || { echo "ERROR: could not retrieve the EDR from the consumer siglet: $EDR" >&2; exit 1; }
echo ">> EDR retrieved from consumer siglet — endpoint: $EDR_ENDPOINT"

PAYLOAD_FILE="$GEN_DIR/payload"
DL_STATUS=$(curl -s -m 30 -o "$PAYLOAD_FILE" -w '%{http_code}' -H "Authorization: Bearer $EDR_TOKEN" "$EDR_ENDPOINT")
[[ "$DL_STATUS" == 200 && -s "$PAYLOAD_FILE" ]] || { echo "ERROR: data download failed (HTTP $DL_STATUS): $(cat "$PAYLOAD_FILE" 2>/dev/null)" >&2; exit 1; }
echo ">> Downloaded payload ($(wc -c < "$PAYLOAD_FILE" | tr -d ' ') bytes):"
cat "$PAYLOAD_FILE"; echo

echo "Cross-VE DSP exchange OK: ${CONS_DID} negotiated '${ASSET_ID}' from ${PROV_DID} (agreement ${AGREEMENT}), established transfer ${TP_ID} (${TP_STATE}) and downloaded the payload from ${EDR_ENDPOINT}"
