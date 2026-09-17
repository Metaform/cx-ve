#!/bin/bash

# FALLBACK — normally not needed. Requests the Catena-X credentials from the VE's issuer by hand.
#
# The regular path needs no action from the vendor: when a verification run onboards the DID, the
# VE's issuer sends a DCP credential offer and IdentityHub requests the offered credentials on its
# own. It does so only for offered credentials whose format profile it recognises, though — an
# offer it cannot map sits unanswered, and the run waits at AWAIT_CREDENTIALS. This script sends
# the same DCP credential request explicitly (Identity API → the issuer's issuance endpoint) and
# waits for the issuer to deliver.
#
# The VE must already have registered the DID as a holder, i.e. the run must be past
# AWAIT_CREDENTIAL_OFFER — the issuer rejects requests from holders it does not know.
#
# Usage:
#   ./vendor-stack/scripts/request-credentials.sh [--issuer-did <did>] [-s|--short-name <name>]
#                                                 [-h|--help]
#
#   --issuer-did   the VE's issuer (default: did:web:issuer.cxve.localhost:issuer)
#
# Environment: VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh), TIMEOUT (seconds, default 180)

source "$(dirname "$0")/lib.sh"

ISSUER_DID="did:web:issuer.cxve.localhost:issuer"
TIMEOUT="${TIMEOUT:-180}"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--short-name|--issuer-did)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
        --issuer-did) ISSUER_DID="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

init
DID=$(participant_did)
PCID=$(participant_context_of "$DID") || exit 1
[[ -n "$PCID" ]] || die "no participant context for $DID — run create-participant.sh first"

# The credential definitions the VE's catenax-profile seeds (and the Membership Hub offers).
REQUEST=$(jq -n --arg issuer "$ISSUER_DID" '{
  issuerDid: $issuer,
  credentials: [
    {id: "membership-credential-def", type: "MembershipCredential",             format: "VC1_0_JWT"},
    {id: "bpn-credential-def",        type: "BpnCredential",                    format: "VC1_0_JWT"},
    {id: "gov-credential-def",        type: "DataExchangeGovernanceCredential", format: "VC1_0_JWT"}
  ]
}')
identity POST "/participants/$PCID/credentials/request" "$REQUEST"
expect_2xx "credential request"
HOLDER_PID=$(printf '%s' "$HTTP_BODY" | jq -r '.holderPid // empty' 2>/dev/null || true)
if [[ -z "$HOLDER_PID" ]]; then
  # the request id is announced in the Location header, which call() does not keep — look it up
  identity GET "/participants/$PCID/credentials/request"
  HOLDER_PID=$(printf '%s' "$HTTP_BODY" | jq -r --arg issuer "$ISSUER_DID" \
    '[.[]? | select(.issuerDid == $issuer)] | last | .holderPid // empty' 2>/dev/null || true)
fi
[[ -n "$HOLDER_PID" ]] || die "credential request sent, but its id could not be determined — check status.sh"
log "credential request $HOLDER_PID sent to $ISSUER_DID"

issued() {
  identity GET "/participants/$PCID/credentials/request/$HOLDER_PID"
  [[ "$HTTP_STATUS" == 200 ]] || return 1
  local status
  status=$(printf '%s' "$HTTP_BODY" | jq -r .status)
  [[ "$status" == ERROR ]] && die "the issuer did not issue: $HTTP_BODY"
  log "request status: $status"
  [[ "$status" == ISSUED ]]
}
poll "$TIMEOUT" 5 "credential request $HOLDER_PID to be issued" issued

echo
echo "Credentials issued to $DID by $ISSUER_DID."
