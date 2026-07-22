#!/bin/bash

# Registers a new partner with the Onboarding API's REST API and follows the onboarding progress
# in the application logs. The registration endpoint returns 200 with an empty body immediately;
# the CX-0006 sequence continues asynchronously and is currently only observable via logs.
#
# Usage:
#   ./scripts/onboard-participant.sh [company-name] [short-name] [cluster-name]
#
#   company-name  display name of the partner company (default: "ACME Corporation")
#   short-name    short name appended to the platform's did:web template to form the participant
#                 DID (default: derived from company-name plus a random suffix, so repeated runs
#                 don't collide in the Tenant Manager)
#   cluster-name  name of the kind cluster created by install-ve.sh, used to locate the
#                 kubeconfig at ~/.kube/<cluster-name>.config for log watching (default: cxve);
#                 an explicitly set KUBECONFIG takes precedence
#
# Environment:
#   API_URL    base URL of the Onboarding API (default: http://cxve.localhost/onboarding, the
#              gateway route created by install-ve.sh)
#   BPN        pre-assigned BPNL; leave unset to have one resolved/created during onboarding
#   NAMESPACE  namespace of the obapi deployment, for log watching (default: edc-v)
#   FOLLOW     "true" forces following the logs even without a terminal (default: tty only);
#              following stops when the onboarding reaches a terminal state
#              (completed / rejected / failed)
#
# Requires: curl, jq; kubectl is optional (progress watching is skipped without it)

set -euo pipefail

API_URL="${API_URL:-http://cxve.localhost/onboarding}"
NAMESPACE="${NAMESPACE:-edc-v}"
BPN="${BPN:-}"
DEPLOYMENT=obapi-cx-ve

NAME="${1:-ACME Corporation}"
RUN_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
DEFAULT_SHORT="$(echo "$NAME" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9' | cut -c1-20)-${RUN_ID:0:6}"
SHORT_NAME="${2:-$DEFAULT_SHORT}"
CLUSTER_NAME="${3:-cxve}"

# Default to the kind cluster's kubeconfig (as written by install-ve.sh) unless the caller set one
if [[ -z "${KUBECONFIG:-}" && -f "$HOME/.kube/$CLUSTER_NAME.config" ]]; then
  export KUBECONFIG="$HOME/.kube/$CLUSTER_NAME.config"
fi

# externalId and the VAT id are unique per run: registrations whose BPN, DID or any unique id
# matches an onboarded partner or an in-flight registration are rejected as duplicates
payload=$(jq -n \
  --arg name "$NAME" \
  --arg shortName "$SHORT_NAME" \
  --arg externalId "$RUN_ID" \
  --arg vatId "DE${RUN_ID:0:8}" \
  --arg bpn "$BPN" \
  '{
    name: $name,
    shortName: $shortName,
    externalId: $externalId,
    city: "Munich",
    streetName: "Otto-Hahn-Ring",
    streetNumber: "6",
    zipCode: "81739",
    region: "BY",
    countryAlpha2Code: "DE",
    bpn: (if $bpn == "" then null else $bpn end),
    uniqueIds: [ { type: "VAT_ID", value: $vatId } ],
    userDetails: [ {
      identityProviderId: "idp-1",
      providerId: "user-1",
      username: "jdoe",
      firstName: "Jane",
      lastName: "Doe",
      email: "jane.doe@example.com"
    } ],
    companyRoles: [ "ACTIVE_PARTICIPANT" ],
    agreements: [],
    autoSubmit: true
  }')

echo "Registering partner \"$NAME\" (shortName=$SHORT_NAME, externalId=$RUN_ID)"

curl -fsS -X POST \
  -H "Content-Type: application/json" \
  -d "$payload" \
  "${API_URL}/api/v2/administration/registration/Network/partnerRegistration"

echo "Registration submitted."

# The process id is assigned server-side and only logged, not returned. Print the start line for
# this registration and, when on a terminal, keep following the onboarding progression.
if command -v kubectl >/dev/null && kubectl get "deployment/$DEPLOYMENT" -n "$NAMESPACE" >/dev/null 2>&1; then
  sleep 2
  kubectl logs "deployment/$DEPLOYMENT" -n "$NAMESPACE" --since=1m 2>/dev/null \
    | grep -F "for participant \"$NAME\"" || true
  if [[ -t 1 || "${FOLLOW:-false}" == "true" ]]; then
    echo "Following onboarding progress until a terminal state (Ctrl-C to stop early):"
    exec 3< <(kubectl logs "deployment/$DEPLOYMENT" -n "$NAMESPACE" -f --since=10s 2>/dev/null)
    LOGS_PID=$!
    while IFS= read -r line <&3; do
      [[ "$line" == *[Oo]nboarding* ]] || continue
      echo "$line"
      # Terminal states end the follow; "paused at ... awaiting async completion" does not match
      # because the state word must directly follow the process id
      if [[ "$line" =~ Onboarding\ [0-9a-f-]+\ (completed|rejected|failed) ]]; then
        break
      fi
    done
    exec 3<&-
    kill "$LOGS_PID" 2>/dev/null || true
  fi
else
  echo "kubectl or deployment not reachable — watch progress with:"
  echo "  kubectl logs -n $NAMESPACE deployment/$DEPLOYMENT -f"
fi
