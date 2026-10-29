#!/bin/bash

# Onboards a new DECADE-X member through the Membership Hub: submits it to POST /api/members
# (which deploys the participant profile to the CFM Tenant Manager and then submits the onboarding
# request to the DECADE-X onboarding API, approved automatically) and then polls GET /api/members/<externalId>
# until the membership reaches a terminal state — CREDENTIALS_OFFERED is success (the member is
# provisioned AND the issuer has been asked to offer it the membership credentials). The hub reads the
# deployed profile's state from the Tenant Manager on every poll, so the participant context id
# appears here as soon as the platform's VPA provisioning has assigned it.
#
# Usage:
#   ./scripts/onboard-participant.sh [-n|--name <company-name>] [-s|--short-name <name>]
#                                    [-u|--api-url <url>] [-h|--help]
#
#   -n, --name        display name of the partner company (default: "ACME Corporation")
#   -s, --short-name  short name appended to the platform's did:web template to form the
#                     participant DID (default: derived from the company name plus a random
#                     suffix, so repeated runs don't collide in the duplicate checks)
#   -u, --api-url     base URL of the Membership Hub (default: http://cxve.localhost/hub,
#                     the gateway route created by install-ve.sh, or the API_URL env var)
#
# Environment:
#   API_URL   base URL of the Membership Hub (default: http://cxve.localhost/hub)
#   FOLLOW    "true" forces polling until a terminal state even without a terminal attached
#             (default: tty only); polling stops on CREDENTIALS_OFFERED / REJECTED / FAILED
#   TIMEOUT   polling budget in seconds (default: 300)
#
# The hub's API is unauthenticated (an operator surface); no OSP client or token is needed —
# the hub itself authenticates to the DECADE-X onboarding API with its own seeded OSP client. The
# DECADE-X-ID is not chosen here: the hub generates one for a member it hosts.
#
# Requires: curl, jq

set -euo pipefail

API_URL="${API_URL:-http://cxve.localhost/hub}"
TIMEOUT="${TIMEOUT:-300}"

NAME="ACME Corporation"
SHORT_NAME=""

usage() {
  cat <<EOF
Usage: $(basename "$0") [-n|--name <company-name>] [-s|--short-name <name>] [-u|--api-url <url>] [-h|--help]

Options:
  -n, --name <company-name>   display name of the partner company (default: "ACME Corporation")
  -s, --short-name <name>     short name forming the participant DID (default: derived from
                              the company name plus a random suffix)
  -u, --api-url <url>         base URL of the Membership Hub
                              (default: http://cxve.localhost/hub)
  -h, --help                  show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--name|-s|--short-name|-u|--api-url)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -n|--name) NAME="$2" ;;
        -s|--short-name) SHORT_NAME="$2" ;;
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
# The VAT id is unique per run: onboarding requests whose DID or registration number matches a
# live membership are rejected as duplicates.
payload=$(jq -n \
  --arg name "$NAME" \
  --arg shortName "$SHORT_NAME" \
  --arg vatId "DE${RUN_ID:0:8}" \
  '{
    dataspace: "decade-x",
    name: $name,
    shortName: $shortName,
    registration: {
      legalEntity: {
        registrationCountry: "DE",
        companyType: "LIMITED_LIABILITY_COMPANY",
        registrationNumbers: [ { scheme: "vatID", value: $vatId } ],
        legalAddress: { street: "Otto-Hahn-Ring 6", postalCode: "81739", locality: "Munich",
                        countryCode: "DE", countryName: "Germany" }
      },
      businessSites: [ { siteName: "Main Site",
                         mainAddress: { street: "Otto-Hahn-Ring 6", postalCode: "81739", locality: "Munich",
                                        countryCode: "DE", countryName: "Germany" } } ],
      legalPerson: { fullName: "Test Operator", email: (("op-" + $vatId) + "@example.com"), role: "Operator" },
      gtc: { versionNumber: "1.0" },
      ucas: [ { useCaseId: "product-conformity", versionNumber: "1.0" } ],
      declarations: { informationAccurate: true, authorisedToAct: true, evidenceMayBeRequested: true }
    }
  }')

echo "Onboarding member \"$NAME\" (shortName=$SHORT_NAME)"

# The POST returns as soon as the first leg is under way — PROVISIONING for a member this VE
# hosts (its profile is deployed on a worker, then registered), or the completed registration for
# one that brought its own DID, which can take a while with slow downstreams; hence the generous
# max-time. A 409 means a live membership already holds this DID.
MEMBERSHIP=$(curl -fsS --max-time 120 -X POST \
  -H "Content-Type: application/json" \
  -d "$payload" \
  "${API_URL}/api/members")

EXTERNAL_ID=$(jq -r .externalId <<<"$MEMBERSHIP")
STATE=$(jq -r .state <<<"$MEMBERSHIP")
DID=$(jq -r .did <<<"$MEMBERSHIP")
echo "Membership submitted: externalId=$EXTERNAL_ID, did=$DID, state=$STATE"

terminal() { [[ "$1" == "CREDENTIALS_OFFERED" || "$1" == "REJECTED" || "$1" == "FAILED" ]]; }

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
  CREDENTIALS_OFFERED)
    echo "Member provisioned and offered its credentials:"
    jq '{externalId, did, memberId, onboardingProcessId, tenantId, participantProfileId, participantContextId}' <<<"$MEMBERSHIP"
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
