#!/bin/bash

# Shows where the vendor participant stands, from the vendor's side: its provisioning, the
# credentials in its wallet (and who issued them) and the certificate exchanges its Certo records —
# every request a consumer made, with its fulfillment and acceptance — or, given an exchange id (a
# VE run shows it, push-certificate.sh prints the ones it fulfils), that one exchange in full.
# Read-only; for diagnosing a verification run that waits on the vendor.
#
# Usage:
#   ./vendor-stack/scripts/status.sh [-d|--dataspace <dataspace>] [--exchange <id>]
#                                    [-s|--short-name <name>] [-h|--help]
#
#   -d, --dataspace   decade-x (default, the only one so far) — the dataspace of the stack
#
#   --exchange <id>   show this certificate exchange in full instead of the list of exchanges
#
# Environment: VENDOR_DATASPACE, VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh)

source "$(dirname "$0")/lib.sh"

EXCHANGE_ID=""

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--dataspace|-s|--short-name|--exchange)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -d|--dataspace) use_dataspace "$2" ;;
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
        --exchange) EXCHANGE_ID="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

init
DID=$(participant_did)

echo "== participant $DID"
PROFILE=$(participant_profile_of "$DID") || exit 1
if [[ -z "$PROFILE" ]]; then
  echo "   not created — run create-participant.sh"
  exit 0
fi
printf '%s' "$PROFILE" | jq -r '"   profile \(.id) (tenant \(.tenantId)), error=\(.error // false)",
  (.vpas[]? | "   vpa \(.type): \(.state)")'
PCID=$(printf '%s' "$PROFILE" | jq -r '.properties["cfm.vpa.state"].participantContextId // empty')
[[ -n "$PCID" ]] || { echo "   no participant context yet"; exit 0; }
echo "   participant context $PCID"

echo
echo "== credentials"
identity GET "/participants/$PCID/credentials"
if [[ "$HTTP_STATUS" == 200 ]]; then
  if [[ "$(printf '%s' "$HTTP_BODY" | jq 'length')" == 0 ]]; then
    echo "   none — the VE has not delivered any (yet)"
  fi
  printf '%s' "$HTTP_BODY" | jq -r '.[] | .verifiableCredential.credential as $c
    | "   \($c.type | map(select(. != "VerifiableCredential")) | join(",")) from \($c.issuer.id // $c.issuer) (state \(.state))"'
else
  echo "   could not read the wallet (HTTP $HTTP_STATUS): $(printf '%s' "$HTTP_BODY" | head -c 300)"
fi

echo
if [[ -n "$EXCHANGE_ID" ]]; then
  echo "== certificate exchange $EXCHANGE_ID"
  certo GET "/participant-contexts/$PCID/certificate-exchanges/$EXCHANGE_ID"
  if [[ "$HTTP_STATUS" == 200 ]]; then
    printf '%s' "$HTTP_BODY" | jq -r '"   certificate \(.certificateId // "none") revision \(.revision // "-")",
      "   fulfillment \(.fulfillmentStatus // "?"), acceptance \(.acceptanceStatus // "none reported")",
      (.fulfillmentErrors // [] | .[] | "   fulfillment error: \(tojson)"),
      (.acceptanceErrors // [] | .[] | "   acceptance error: \(tojson)")'
  else
    echo "   could not read the exchange (HTTP $HTTP_STATUS): $(printf '%s' "$HTTP_BODY" | head -c 300)"
  fi
else
  # The request query lists one fulfillment status at a time; the acceptance is on the exchange.
  echo "== certificate exchanges"
  FOUND=0
  for status in RECEIVED FULFILLED DECLINED; do
    certo POST "/participant-contexts/$PCID/certificate-requests/query" "$(jq -n --arg status "$status" '{status: $status}')"
    if [[ "$HTTP_STATUS" != 200 ]]; then
      echo "   could not list the $status exchanges (HTTP $HTTP_STATUS): $(printf '%s' "$HTTP_BODY" | head -c 300)"
      continue
    fi
    PAGE="$HTTP_BODY"
    for exchange in $(printf '%s' "$PAGE" | jq -r '.items[].exchangeId'); do
      FOUND=1
      line=$(printf '%s' "$PAGE" | jq -r --arg id "$exchange" \
        '.items[] | select(.exchangeId == $id) | "   \(.exchangeId) \(.certificateType) from \(.counterpartyId) at \(.requestedAt // "?"): \(.status)"')
      certo GET "/participant-contexts/$PCID/certificate-exchanges/$exchange"
      if [[ "$HTTP_STATUS" == 200 ]]; then
        line+=$(printf '%s' "$HTTP_BODY" | jq -r '", acceptance \(.acceptanceStatus // "none reported")"')
      fi
      echo "$line"
    done
  done
  (( FOUND )) || echo "   none — no consumer has requested a certificate (yet)"
fi
