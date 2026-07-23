#!/bin/bash

# Cross-VE DSP demo: as the provider VE's newest onboarded participant, offers an asset under an
# open use-policy; as the consumer VE's newest participant, requests the provider's catalog,
# negotiates a contract and establishes an HttpData-PULL transfer process through the
# participants' siglet data planes. Succeeds when the negotiation reaches FINALIZED and the
# transfer process reaches STARTED, and prints the agreement and transfer-process ids.
#
# Prerequisites: both VEs installed (install-ve.sh), a participant onboarded in each
# (onboard-participant.sh) and the VEs connected (connect-ves.sh — routes, DNS forwarding,
# mutual issuer trust incl. supported credential types).
#
# Management-API access follows the platform's own seeding pattern: a helper pod with the
# `seed-jobs` ServiceAccount and a projected jwtlet subject token exchanges it (RFC 8693,
# resource=issuer scope=admin) for an EDC Management API token and calls
# controlplane:8081/api/mgmt/v5beta in-cluster.
#
# Usage:
#   ./scripts/dsp-demo.sh [--pc <cluster>] [--pd <domain>] [--cc <cluster>] [--cd <domain>]
#                         [--asset <id>] [-h|--help]

set -euo pipefail

PROV_CLUSTER=ve1; PROV_DOMAIN=ve1.local
CONS_CLUSTER=ve2; CONS_DOMAIN=ve2.local
# Default derived from the provider participant context after discovery: ids are unique across
# participant contexts in the store, so a fixed id would 409 against an older participant's
# asset without ever appearing in this participant's catalog
ASSET_ID=""
NAMESPACE=edc-v

usage() {
  cat <<EOF
Usage: $(basename "$0") [--pc <cluster>] [--pd <domain>] [--cc <cluster>] [--cd <domain>]
                        [--asset <id>] [-h|--help]

Options (defaults match the dual-VE install convention):
  --pc/--pd   provider cluster / DNS domain (default: ve1 / ve1.local)
  --cc/--cd   consumer cluster / DNS domain (default: ve2 / ve2.local)
  --asset     asset id to offer and negotiate (default: demo-asset-<provider-ctx-prefix>)
  -h, --help  show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pc|--pd|--cc|--cd|--asset)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        --pc) PROV_CLUSTER="$2" ;; --pd) PROV_DOMAIN="$2" ;;
        --cc) CONS_CLUSTER="$2" ;; --cd) CONS_DOMAIN="$2" ;;
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

# ---- helper pod with seed-jobs SA + projected jwtlet subject token --------------------------
ensure_probe() { # <cluster>
  local kc; kc=$(kubeconfig "$1")
  # Pod specs are largely immutable — recreate instead of apply-over (a leftover pod from an
  # earlier run may carry a different spec)
  kubectl --kubeconfig "$kc" delete pod mgmt-probe -n "$NAMESPACE" --ignore-not-found --wait >/dev/null
  cat > "$GEN_DIR/probe.yaml" <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: mgmt-probe
  namespace: ${NAMESPACE}
spec:
  serviceAccountName: seed-jobs
  restartPolicy: Never
  containers:
    - name: probe
      image: alpine:latest
      # The marker file signals that curl/jq are actually installed — pod readiness alone
      # races the package installation
      command: ["sh", "-c", "apk add --no-cache curl jq >/dev/null 2>&1 && touch /tmp/pkgs-ready; sleep 7200"]
      env:
        # The VE's DNS domain, injected so the helper needs no resolv.conf guesswork (the
        # host's own search domains leak into pod resolv.conf on some runners)
        - name: VE_DOMAIN
          value: "$2"
      volumeMounts:
        - name: jwtlet-subject-token
          mountPath: /var/run/secrets/jwtlet
          readOnly: true
  volumes:
    - name: jwtlet-subject-token
      projected:
        sources:
          - serviceAccountToken:
              path: token
              audience: https://kubernetes.default.svc.cluster.local
              expirationSeconds: 3600
EOF
  kubectl --kubeconfig "$kc" apply -f "$GEN_DIR/probe.yaml" >/dev/null
  kubectl --kubeconfig "$kc" wait pod/mgmt-probe -n "$NAMESPACE" --for=condition=Ready --timeout=120s >/dev/null
  local i
  for i in $(seq 1 30); do
    kubectl --kubeconfig "$kc" exec mgmt-probe -n "$NAMESPACE" -- test -f /tmp/pkgs-ready 2>/dev/null && break
    [[ $i -eq 30 ]] && { echo "ERROR: $1: probe pod packages not ready" >&2; return 1; }
    sleep 2
  done

  # In-pod management-API helper: exchanges the projected SA token and calls v5beta
  cat > "$GEN_DIR/m.sh" <<'EOF'
#!/bin/sh
# usage: m.sh METHOD PATH ['-' to read JSON body from stdin]
D=$VE_DOMAIN
SA=$(cat /var/run/secrets/jwtlet/token)
TOK=""
for i in 1 2 3 4 5; do
  TOK=$(curl -s -m 10 -X POST "http://jwtlet.edc-v.svc.$D:8080/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
    --data-urlencode "subject_token=$SA" \
    --data-urlencode 'resource=issuer' --data-urlencode 'scope=admin' --data-urlencode 'audience=edcv' | jq -r .access_token)
  [ -n "$TOK" ] && [ "$TOK" != null ] && break
  sleep 3
done
[ -n "$TOK" ] && [ "$TOK" != null ] || { echo "token exchange failed" >&2; exit 1; }
M=$1; P=$2; shift 2
if [ "${1:-}" = '-' ]; then
  curl -s -m 30 -w '\n%{http_code}' -X "$M" "http://controlplane.edc-v.svc.$D:8081/api/mgmt/v5beta$P" \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' --data @-
else
  curl -s -m 30 -w '\n%{http_code}' -X "$M" "http://controlplane.edc-v.svc.$D:8081/api/mgmt/v5beta$P" \
    -H "Authorization: Bearer $TOK"
fi
EOF
  kubectl --kubeconfig "$kc" cp "$GEN_DIR/m.sh" "${NAMESPACE}/mgmt-probe:/tmp/m.sh" >/dev/null
  kubectl --kubeconfig "$kc" exec mgmt-probe -n "$NAMESPACE" -- chmod +x /tmp/m.sh
}

# mgmt <cluster> <method> <path> [json-file] -> body on stdout, fails on HTTP >= 400 (409 ok)
mgmt() {
  local kc; kc=$(kubeconfig "$1")
  local out status body
  if [[ $# -ge 4 ]]; then
    out=$(kubectl --kubeconfig "$kc" exec -i mgmt-probe -n "$NAMESPACE" -- /tmp/m.sh "$2" "$3" - < "$4")
  else
    out=$(kubectl --kubeconfig "$kc" exec mgmt-probe -n "$NAMESPACE" -- /tmp/m.sh "$2" "$3")
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

echo ">> Preparing management probe pods"
ensure_probe "$PROV_CLUSTER" "$PROV_DOMAIN"
ensure_probe "$CONS_CLUSTER" "$CONS_DOMAIN"

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
POLICY_ID="policy-open-${PROV_CTX:0:8}"
echo ">> Provider:  $PROV_DID ($PROV_CTX)"
echo ">> Consumer:  $CONS_DID ($CONS_CTX)"

# ---- provider: asset + open policy + contract definition ------------------------------------
cat > "$GEN_DIR/asset.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2"],
  "@type": "Asset",
  "@id": "${ASSET_ID}",
  "properties": {"name": "Cross-VE DSP demo asset"},
  "dataAddress": {"@type": "DataAddress", "type": "HttpData", "baseUrl": "http://jwtlet.${NAMESPACE}.svc.${PROV_DOMAIN}:8080/.well-known/jwks.json"}
}
EOF
# ODRL requires at least one rule for a negotiable offer; an unconstrained "use" permission is
# the closest thing to an open policy
cat > "$GEN_DIR/policy.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2", "http://www.w3.org/ns/odrl.jsonld"],
  "@type": "PolicyDefinition",
  "@id": "${POLICY_ID}",
  "policy": {"@context": "http://www.w3.org/ns/odrl.jsonld", "@type": "Set", "permission": [{"action": "use"}]}
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
mgmt "$PROV_CLUSTER" POST "/participants/${PROV_CTX}/policydefinitions" "$GEN_DIR/policy.json" >/dev/null \
  || mgmt "$PROV_CLUSTER" PUT "/participants/${PROV_CTX}/policydefinitions/${POLICY_ID}" "$GEN_DIR/policy.json" >/dev/null
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
OFFER_ID=$(printf '%s' "$CATALOG" | jq -r --arg a "$ASSET_ID" \
  '.dataset | if type=="array" then . else [.] end | .[] | select(.["@id"]==$a) | .hasPolicy | if type=="array" then .[0] else . end | .["@id"]')
[[ -n "$OFFER_ID" && "$OFFER_ID" != null ]] || { echo "ERROR: asset '$ASSET_ID' not found in catalog: $CATALOG" >&2; exit 1; }
echo ">> Catalog contains '$ASSET_ID', offer: $OFFER_ID"

# ---- consumer: contract negotiation ---------------------------------------------------------
cat > "$GEN_DIR/negotiation.json" <<EOF
{
  "@context": ["https://w3id.org/edc/connector/management/v2", "http://www.w3.org/ns/odrl.jsonld"],
  "@type": "ContractRequest",
  "counterPartyAddress": "${PROV_DSP}",
  "protocol": "cx-neptune",
  "policy": {
    "@context": "http://www.w3.org/ns/odrl.jsonld",
    "@id": "${OFFER_ID}",
    "@type": "Offer",
    "assigner": "${PROV_DID}",
    "target": "${ASSET_ID}",
    "permission": [{"action": "use"}]
  }
}
EOF
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
