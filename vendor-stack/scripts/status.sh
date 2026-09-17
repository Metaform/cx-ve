#!/bin/bash

# Shows where the vendor participant stands, from the vendor's side: its provisioning, the
# credentials in its wallet (and who issued them) and — given the id push-certificate.sh printed —
# a certificate exchange as the vendor's Certo records it. Read-only; for diagnosing a verification
# run that waits on the vendor.
#
# Usage:
#   ./vendor-stack/scripts/status.sh [--exchange <id>] [-s|--short-name <name>] [-h|--help]
#
#   --exchange <id>   also show this certificate exchange (Certo keeps no provider-side listing)
#
# Environment: VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh)

source "$(dirname "$0")/lib.sh"

EXCHANGE_ID=""

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--short-name|--exchange)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
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

if [[ -n "$EXCHANGE_ID" ]]; then
  echo
  echo "== certificate exchange $EXCHANGE_ID"
  certo GET "/participant-contexts/$PCID/certificate-exchanges/$EXCHANGE_ID"
  if [[ "$HTTP_STATUS" == 200 ]]; then
    printf '%s' "$HTTP_BODY" | jq -r '"   fulfillment \(.fulfillmentStatus // "?"), acceptance \(.acceptanceStatus // "?")"'
  else
    echo "   could not read the exchange (HTTP $HTTP_STATUS): $(printf '%s' "$HTTP_BODY" | head -c 300)"
  fi
fi
