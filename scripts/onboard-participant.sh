#!/bin/bash

# Registers a new partner with the Onboarding API's REST API and follows the onboarding progress
# in the application logs until a terminal state. The registration endpoint returns 200 with the
# onboarding process id once the synchronous part of the flow has run; the CX-0006 sequence
# continues asynchronously and its progression is currently only observable via logs.
#
# Usage:
#   ./scripts/onboard-participant.sh [-n|--name <company-name>] [-s|--short-name <name>]
#                                    [-b|--bpn <bpn>] [-c|--cluster <cluster-name>]
#                                    [-N|--namespace <ns>] [-u|--api-url <url>] [-h|--help]
#
#   -n, --name        display name of the partner company (default: "ACME Corporation")
#   -s, --short-name  short name appended to the platform's did:web template to form the
#                     participant DID (default: derived from the company name plus a random
#                     suffix, so repeated runs don't collide in the Tenant Manager)
#   -b, --bpn         pre-assigned BPNL of the partner (default: derived from the run id,
#                     unique per run; the API itself would also assign one if omitted)
#   -c, --cluster     name of the kind cluster created by install-ve.sh, used to locate the
#                     kubeconfig at ~/.kube/<cluster-name>.config for log watching
#                     (default: cxve); an explicitly set KUBECONFIG takes precedence
#   -N, --namespace   namespace of the obapi deployment, for log watching (default: edc-v,
#                     or the NAMESPACE env var)
#   -u, --api-url     base URL of the Onboarding API (default: http://cxve.localhost/onboarding,
#                     or the API_URL env var)
#
# Environment:
#   API_URL            base URL of the Onboarding API (default: http://cxve.localhost/onboarding,
#                      the gateway route created by install-ve.sh)
#   AUTH_URL           base URL of the OSP IdP through the gateway
#                      (default: http://cxve.localhost/auth/osp)
#   ACCESS_TOKEN       bearer token for the API; when unset, one is fetched via
#                      client_credentials from the OSP IdP — see below
#   OSP_CLIENT_ID      OSP client for the token request (default: osp-client, the client the
#                      umbrella chart seeds)
#   OSP_CLIENT_SECRET  its secret (default: osp-secret)
#   BPN                pre-assigned BPNL; --bpn takes precedence (default: derived from the run id)
#   NAMESPACE          namespace of the obapi deployment, for log watching (default: edc-v)
#   FOLLOW             "true" forces following the logs even without a terminal (default: tty
#                      only); following stops when the onboarding reaches a terminal state
#                      (completed / rejected / failed)
#
# Requires: curl, jq; kubectl is optional (progress watching is skipped without it)

set -euo pipefail

API_URL="${API_URL:-http://cxve.localhost/onboarding}"
NAMESPACE="${NAMESPACE:-edc-v}"
BPN="${BPN:-}"
DEPLOYMENT=cx-ve-onboarding-api

NAME="ACME Corporation"
SHORT_NAME=""
CLUSTER_NAME=cxve

usage() {
  cat <<EOF
Usage: $(basename "$0") [-n|--name <company-name>] [-s|--short-name <name>] [-b|--bpn <bpn>] [-c|--cluster <cluster-name>] [-h|--help]

Options:
  -n, --name <company-name>   display name of the partner company (default: "ACME Corporation")
  -s, --short-name <name>     short name forming the participant DID (default: derived from
                              the company name plus a random suffix)
  -b, --bpn <bpn>             pre-assigned BPNL; a required registration field (default:
                              derived from the run id, unique per run)
  -c, --cluster <name>        kind cluster whose kubeconfig (~/.kube/<name>.config) is used
                              for log watching (default: cxve)
  -N, --namespace <ns>        namespace of the obapi deployment (default: edc-v)
  -u, --api-url <url>         base URL of the Onboarding API
                              (default: http://cxve.localhost/onboarding)
  -h, --help                  show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--name|-s|--short-name|-b|--bpn|-c|--cluster|-N|--namespace|-u|--api-url)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -n|--name) NAME="$2" ;;
        -s|--short-name) SHORT_NAME="$2" ;;
        -b|--bpn) BPN="$2" ;;
        -c|--cluster) CLUSTER_NAME="$2" ;;
        -N|--namespace) NAMESPACE="$2" ;;
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
# bpn is a required field of the registration payload (rejected with 400 without it); default
# to a BPNL derived from the run id so it is unique per run, like externalId and the VAT id
BPN="${BPN:-BPNL$(printf '%s' "${RUN_ID//-/}" | cut -c1-12 | tr '[:lower:]' '[:upper:]')}"

# Default to the kind cluster's kubeconfig (as written by install-ve.sh) unless the caller set one
if [[ -z "${KUBECONFIG:-}" && -f "$HOME/.kube/$CLUSTER_NAME.config" ]]; then
  export KUBECONFIG="$HOME/.kube/$CLUSTER_NAME.config"
fi

# The API requires a bearer token from the VE's OSP IdP (any valid token registers a partner;
# only the configure_partner_registration scope may set status callbacks). Unless the caller
# supplied one, fetch it exactly as an external onboarding service provider would: OAuth2
# client_credentials against the IdP's gateway route, with the osp-client the umbrella chart
# seeds.
AUTH_URL="${AUTH_URL:-http://cxve.localhost/auth/osp}"
OSP_CLIENT_ID="${OSP_CLIENT_ID:-osp-client}"
OSP_CLIENT_SECRET="${OSP_CLIENT_SECRET:-osp-secret}"
if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  ACCESS_TOKEN=$(curl -fsS -u "${OSP_CLIENT_ID}:${OSP_CLIENT_SECRET}" \
    -d "grant_type=client_credentials" \
    -d "scope=configure_partner_registration" \
    "${AUTH_URL}/oauth2/token" | jq -r .access_token)
  if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "null" ]]; then
    echo "Error: client_credentials at ${AUTH_URL}/oauth2/token returned no access_token" >&2
    exit 1
  fi
fi

# bpn stays in the payload although the API no longer requires it: a caller-known BPN keeps
# repeated runs deterministic, and the duplicate check only sees a submitted BPN.
# externalId and the VAT id are unique per run: registrations whose BPN, DID or any unique id
# matches an onboarded partner or an in-flight registration are rejected as duplicates.
# The ACTIVE "Catena-X" agreement matters: the MembershipCredential's memberOf claim is derived
# from the ACTIVE agreement ids, and the Catena-X CEL policy (cx …/policy/Membership) requires
# memberOf == 'Catena-X' — without it the participant fails credential-constrained policies.
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
    bpn: $bpn,
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
    agreements: [ { agreementId: "Catena-X", consentStatus: "ACTIVE" } ],
    autoSubmit: true
  }')

echo "Registering partner \"$NAME\" (shortName=$SHORT_NAME, bpn=$BPN, externalId=$RUN_ID)"

PROCESS_ID=$(curl -fsS -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d "$payload" \
  "${API_URL}/api/v2/administration/registration/Network/partnerRegistration")

echo "Registration submitted (process id: ${PROCESS_ID:-unknown})."

if command -v kubectl >/dev/null && kubectl get "deployment/$DEPLOYMENT" -n "$NAMESPACE" >/dev/null 2>&1; then
  if [[ -t 1 || "${FOLLOW:-false}" == "true" ]]; then
    echo "Following onboarding progress until a terminal state (Ctrl-C to stop early):"
    # The pod output is parsed by POLLING full log snapshots rather than attaching a -f stream:
    # each snapshot contains the container's whole log, so a terminal state reached at ANY point
    # — including before this loop started; the registration call itself runs the flow for up to
    # tens of seconds — is always seen. This avoids every failure mode the streaming variants
    # had: --since windows that miss already-written lines (and --since-time comparing the LOCAL
    # clock to the NODE clock, which drift on Docker Desktop), a stream that dies with the pod,
    # and bash-3.2's read -t reporting a quiet stream like EOF.
    # Only this run's lines are shown: the process id marks them all — the start line, the
    # transitions and the terminal line ("nboarding" is the fallback against an older API that
    # returns no process id).
    if [[ -n "$PROCESS_ID" ]]; then FILTER="$PROCESS_ID"; else FILTER="nboarding"; fi
    RESULT=""
    PRINTED=0
    DEADLINE=$(( $(date +%s) + 300 ))
    while (( $(date +%s) < DEADLINE )); do
      LINES=$(kubectl logs "deployment/$DEPLOYMENT" -n "$NAMESPACE" --tail=-1 2>/dev/null \
        | grep -F "$FILTER" || true)
      COUNT=$(printf '%s' "$LINES" | grep -c . || true)
      # A shrinking count means the container restarted and its log reset; print from the top
      if (( COUNT < PRINTED )); then PRINTED=0; fi
      if (( COUNT > PRINTED )); then
        printf '%s\n' "$LINES" | tail -n $(( COUNT - PRINTED ))
        PRINTED=$COUNT
      fi
      # Terminal states end the follow; "paused at ... awaiting async completion" does not match
      # because the state word must directly follow the process id
      if [[ "$LINES" =~ Onboarding\ ${PROCESS_ID:-[0-9a-f-]+}\ (completed|rejected|failed) ]]; then
        RESULT="${BASH_REMATCH[1]}"
        break
      fi
      sleep 2
    done
    if [[ "$RESULT" != "completed" ]]; then
      echo "Onboarding did not complete (terminal state: ${RESULT:-none within timeout})" >&2
      exit 1
    fi
  fi
else
  echo "kubectl or deployment not reachable — watch progress with:"
  echo "  kubectl logs -n $NAMESPACE deployment/$DEPLOYMENT -f"
fi
