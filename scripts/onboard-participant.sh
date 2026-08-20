#!/bin/bash

# Onboards a new member through the Membership Hub: submits it to POST /api/members (which runs
# the CX-0006 registration against the Onboarding API and, on its confirmation, deploys the
# participant profile to the CFM Tenant Manager) and then polls GET /api/members/<externalId>
# until the membership reaches a terminal state — PROVISIONED is success. The hub reads the
# deployed profile's state from the Tenant Manager on every poll, so the participant context id
# appears here as soon as the platform's VPA provisioning has assigned it.
#
# Usage:
#   ./scripts/onboard-participant.sh [-n|--name <company-name>] [-s|--short-name <name>]
#                                    [-b|--bpn <bpn>] [-u|--api-url <url>] [-h|--help]
#
#   -n, --name        display name of the partner company (default: "ACME Corporation")
#   -s, --short-name  short name appended to the platform's did:web template to form the
#                     participant DID (default: derived from the company name plus a random
#                     suffix, so repeated runs don't collide in the duplicate checks)
#   -b, --bpn         pre-assigned BPNL of the partner — a required field of the hub's API
#                     (default: derived from the run id, unique per run)
#   -u, --api-url     base URL of the Membership Hub (default: http://cxve.localhost/hub,
#                     the gateway route created by install-ve.sh, or the API_URL env var)
#
# Environment:
#   API_URL   base URL of the Membership Hub (default: http://cxve.localhost/hub)
#   BPN       pre-assigned BPNL; --bpn takes precedence (default: derived from the run id)
#   FOLLOW    "true" forces polling until a terminal state even without a terminal attached
#             (default: tty only); polling stops on PROVISIONED / REJECTED / FAILED
#   TIMEOUT   polling budget in seconds (default: 300)
#
# The hub's API is unauthenticated (an operator surface); no OSP client or token is needed —
# the hub itself authenticates to the Onboarding API with its own seeded OSP client.
#
# Requires: curl, jq

set -euo pipefail

API_URL="${API_URL:-http://cxve.localhost/hub}"
BPN="${BPN:-}"
TIMEOUT="${TIMEOUT:-300}"

NAME="ACME Corporation"
SHORT_NAME=""

usage() {
  cat <<EOF
Usage: $(basename "$0") [-n|--name <company-name>] [-s|--short-name <name>] [-b|--bpn <bpn>] [-u|--api-url <url>] [-h|--help]

Options:
  -n, --name <company-name>   display name of the partner company (default: "ACME Corporation")
  -s, --short-name <name>     short name forming the participant DID (default: derived from
                              the company name plus a random suffix)
  -b, --bpn <bpn>             pre-assigned BPNL; a required field of the hub's API (default:
                              derived from the run id, unique per run)
  -u, --api-url <url>         base URL of the Membership Hub
                              (default: http://cxve.localhost/hub)
  -h, --help                  show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--name|-s|--short-name|-b|--bpn|-u|--api-url)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -n|--name) NAME="$2" ;;
        -s|--short-name) SHORT_NAME="$2" ;;
        -b|--bpn) BPN="$2" ;;
        -u|--api-url) API_URL="$2" ;;
      esac
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown argument '$1'" >&2
      usage >&2
      exit 1
      ;;
  esac
done

RUN_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
DEFAULT_SHORT="$(echo "$NAME" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9' | cut -c1-20)-${RUN_ID:0:6}"
SHORT_NAME="${SHORT_NAME:-$DEFAULT_SHORT}"
# The BPN and the VAT id are unique per run: registrations whose BPN, DID or any unique id
# matches an onboarded partner or an in-flight registration are rejected as duplicates.
BPN="${BPN:-BPNL$(printf '%s' "${RUN_ID//-/}" | cut -c1-12 | tr '[:lower:]' '[:upper:]')}"

# The ACTIVE "Catena-X" agreement matters: the MembershipCredential's memberOf claim is derived
# from the ACTIVE agreement ids, and the Catena-X CEL policy (cx …/policy/Membership) requires
# memberOf == 'Catena-X' — without it the participant fails credential-constrained policies.
payload=$(jq -n \
  --arg name "$NAME" \
  --arg shortName "$SHORT_NAME" \
  --arg vatId "DE${RUN_ID:0:8}" \
  --arg bpn "$BPN" \
  '{
    name: $name,
    shortName: $shortName,
    bpn: $bpn,
    uniqueIds: [ { type: "VAT_ID", value: $vatId } ],
    companyRoles: [ "ACTIVE_PARTICIPANT" ],
    agreements: [ { agreementId: "Catena-X", consentStatus: "ACTIVE" } ]
  }')

echo "Onboarding member \"$NAME\" (shortName=$SHORT_NAME, bpn=$BPN)"

# The POST runs registration AND profile deployment synchronously; with slow downstreams it can
# take a while, hence the generous max-time.
MEMBERSHIP=$(curl -fsS --max-time 120 -X POST \
  -H "Content-Type: application/json" \
  -d "$payload" \
  "${API_URL}/api/members")

EXTERNAL_ID=$(jq -r .externalId <<<"$MEMBERSHIP")
STATE=$(jq -r .state <<<"$MEMBERSHIP")
DID=$(jq -r .did <<<"$MEMBERSHIP")
echo "Membership submitted: externalId=$EXTERNAL_ID, did=$DID, state=$STATE"

terminal() { [[ "$1" == "PROVISIONED" || "$1" == "REJECTED" || "$1" == "FAILED" ]]; }

# REGISTERING means the registration did not confirm within the submitting call; the hub never
# provisions such a record, so polling would wait forever.
if [[ "$STATE" == "REGISTERING" ]]; then
  echo "Registration was not confirmed within the submission — the hub will not provision this member." >&2
  exit 1
fi

if ! terminal "$STATE" && [[ -t 1 || "${FOLLOW:-false}" == "true" ]]; then
  echo "Polling membership state until terminal (Ctrl-C to stop early):"
  DEADLINE=$(( $(date +%s) + TIMEOUT ))
  while ! terminal "$STATE" && (( $(date +%s) < DEADLINE )); do
    sleep 3
    MEMBERSHIP=$(curl -fsS "${API_URL}/api/members/${EXTERNAL_ID}")
    NEW_STATE=$(jq -r .state <<<"$MEMBERSHIP")
    if [[ "$NEW_STATE" != "$STATE" ]]; then
      STATE="$NEW_STATE"
      echo "  state: $STATE"
    fi
  done
fi

case "$STATE" in
  PROVISIONED)
    echo "Member provisioned:"
    jq '{externalId, did, bpn, onboardingProcessId, cfmTenantId, cfmParticipantProfileId, edcParticipantContextId}' <<<"$MEMBERSHIP"
    ;;
  REJECTED|FAILED)
    echo "Onboarding ended as ${STATE}: $(jq -r '.failureReason // "no reason recorded"' <<<"$MEMBERSHIP")" >&2
    exit 1
    ;;
  *)
    echo "Onboarding did not reach a terminal state within ${TIMEOUT}s (last state: $STATE)" >&2
    echo "Keep watching with: curl -s ${API_URL}/api/members/${EXTERNAL_ID} | jq" >&2
    exit 1
    ;;
esac
