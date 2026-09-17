#!/bin/bash
# Shared plumbing for the vendor operator scripts — sourced, not executed.
#
# Everything is driven from the host through the vendor stack's gateway, the way an operator
# would drive their own platform: Traefik (prefix rewrites) → clearglass (JWT scope check) → API.
#
# Auth: kubectl mints a TokenRequest token for the platform's `seed-jobs` ServiceAccount — the
# same token a pod would get projected — and jwtlet exchanges it (RFC 8693) at /api/auth/token for
# a scoped access token. seed-jobs is mapped to the `issuer` context with the role scopes
# (admin → management-api:admin …, cfm-read/cfm-write → tenant-manager-api:*), and — by the vendor
# chart's certo jwtlet seed — to `sudo` with certo-mgmt-api:*.
#
# Gateway paths (prefix rewrites by the platform's HTTPRoutes):
#   /api/auth        → jwtlet                        (token exchange)
#   /api/management  → controlplane /api/mgmt        (management API, v5)
#   /api/tm          → tenant-manager /api/v1alpha1
#   /api/identity    → identityhub /api/identity/v1
#   /api/certo       → certo /                       (management API at /management/v1)

set -euo pipefail

VENDOR_CLUSTER="${VENDOR_CLUSTER:-vendor}"
VENDOR_HOST="${VENDOR_HOST:-vendor.localhost}"
VENDOR_PORT="${VENDOR_PORT:-8080}"
VENDOR_URL="${VENDOR_URL:-http://${VENDOR_HOST}:${VENDOR_PORT}}"
NAMESPACE=edc-v
AUDIENCE=edcv
KUBECONFIG_FILE="$HOME/.kube/${VENDOR_CLUSTER}.config"

# The vendor participant this stack hosts. EDC object ids (the ccm-api asset in particular) are
# unique across ALL participant contexts of a control plane, so the stack hosts exactly one.
PARTICIPANT_SHORT_NAME="${PARTICIPANT_SHORT_NAME:-vendor-participant}"

# did:web authority of this stack: the port is percent-encoded into it (see chart/values.yaml)
did_authority() {
  if [[ "$VENDOR_PORT" == 80 ]]; then echo "identity.${VENDOR_HOST}"; else echo "identity.${VENDOR_HOST}%3A${VENDOR_PORT}"; fi
}
participant_did() { echo "did:web:$(did_authority):${PARTICIPANT_SHORT_NAME}"; }

log() { echo ">> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

# Checks the tools and the cluster, and mints the subject token every API helper exchanges. Call
# once at the top of a script, in the main shell.
init() { # <tool...>
  local tool
  for tool in kubectl curl jq "$@"; do
    command -v "$tool" >/dev/null 2>&1 || die "'$tool' is required"
  done
  [[ -r "$KUBECONFIG_FILE" ]] || die "no kubeconfig at $KUBECONFIG_FILE — was the stack installed with vendor-stack/install.sh?"
  # Valid 1h — longer than any script here runs. Access tokens, in contrast, are exchanged per
  # request: the helpers run in command substitutions, where a cache would not survive, and a long
  # poll would otherwise outlive one.
  SUBJECT_TOKEN=$(kubectl --kubeconfig "$KUBECONFIG_FILE" create token seed-jobs -n "$NAMESPACE" \
    --audience=https://kubernetes.default.svc.cluster.local --duration=3600s) \
    || die "could not mint a token for the seed-jobs ServiceAccount in cluster '$VENDOR_CLUSTER'"
}

# ---- tokens -----------------------------------------------------------------------------------

xtoken() { # <resource> <scope> -> access token for the seed-jobs identity
  local resource="$1" scope="$2" token i
  [[ -n "${SUBJECT_TOKEN:-}" ]] || die "init was not called"
  for i in 1 2 3 4 5; do
    token=$(curl -s -m 10 -X POST "$VENDOR_URL/api/auth/token" \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
      --data-urlencode "subject_token=$SUBJECT_TOKEN" \
      --data-urlencode 'subject_token_type=urn:ietf:params:oauth:token-type:jwt' \
      --data-urlencode "resource=$resource" --data-urlencode "scope=$scope" \
      --data-urlencode "audience=$AUDIENCE" | jq -r '.access_token // empty' 2>/dev/null || true)
    [[ -n "$token" ]] && { printf '%s' "$token"; return 0; }
    sleep 3
  done
  die "token exchange failed at $VENDOR_URL/api/auth/token (resource $resource, scope $scope)"
}

# ---- HTTP -------------------------------------------------------------------------------------

# call <token> <method> <url> [json-body] -> sets HTTP_STATUS and HTTP_BODY (does not fail).
# A gateway error (502/503/504 with Traefik's plain-text body) is retried: the request never reached
# the backend. A 502 with a JSON body is NOT — that is the management API reporting a counterparty's
# answer (e.g. "Counter Party responded with … code=401"), which the caller must see.
call() {
  local token="$1" method="$2" url="$3" body="${4:-}" out attempt
  for attempt in 1 2 3 4 5; do
    if [[ -n "$body" ]]; then
      # body on stdin: a document upload does not fit on a command line
      out=$(printf '%s' "$body" | curl -s -m 60 -w '\n%{http_code}' -X "$method" "$url" \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary @-)
    else
      out=$(curl -s -m 60 -w '\n%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $token")
    fi
    HTTP_STATUS=$(printf '%s' "$out" | tail -1)
    HTTP_BODY=$(printf '%s' "$out" | sed '$d')
    case "$HTTP_STATUS" in
      502|503|504)
        case "$HTTP_BODY" in
          \[*|\{*) return 0 ;;
        esac
        log "$method $url: HTTP $HTTP_STATUS (gateway/backend not settled), retry $attempt"
        sleep 3
        ;;
      *) return 0 ;;
    esac
  done
}

expect_2xx() { # <what>
  [[ "$HTTP_STATUS" =~ ^2 ]] || die "$1 failed with HTTP $HTTP_STATUS: $HTTP_BODY"
}

# The API helpers: <method> <path> [json] -> HTTP_STATUS / HTTP_BODY. The token is fetched in a
# separate statement so a failed exchange stops the script (an argument's command substitution
# would not trip set -e).

mgmt() { # management API v5, as the issuer context's admin
  local token
  token=$(xtoken issuer admin) || exit 1
  call "$token" "$1" "$VENDOR_URL/api/management/v5$2" "${3:-}"
}

tm() { # tenant manager
  local token
  token=$(xtoken issuer "cfm-read cfm-write") || exit 1
  call "$token" "$1" "$VENDOR_URL/api/tm$2" "${3:-}"
}

certo() { # certo management API
  local token
  token=$(xtoken sudo "certo-mgmt-api:read certo-mgmt-api:write") || exit 1
  call "$token" "$1" "$VENDOR_URL/api/certo/management/v1$2" "${3:-}"
}

identity() { # IdentityHub identity API, as the issuer context's admin
  local token
  token=$(xtoken issuer admin) || exit 1
  call "$token" "$1" "$VENDOR_URL/api/identity$2" "${3:-}"
}

# ---- lookups ----------------------------------------------------------------------------------

# The participant context of a DID on the vendor's control plane; empty when it doesn't exist.
participant_context_of() { # <did>
  mgmt GET /participants
  expect_2xx "listing participant contexts"
  printf '%s' "$HTTP_BODY" | jq -r --arg did "$1" '.[] | select(.identity == $did) | .["@id"]' | head -1
}

# The tenant-manager participant profile carrying a DID, as JSON with its tenant id added under
# "tenantId"; empty when there is none.
participant_profile_of() { # <did>
  local tenant profile
  tm GET /tenants
  expect_2xx "listing tenants"
  for tenant in $(printf '%s' "$HTTP_BODY" | jq -r '.[].id'); do
    tm GET "/tenants/$tenant/participant-profiles"
    [[ "$HTTP_STATUS" == 200 ]] || continue
    profile=$(printf '%s' "$HTTP_BODY" | jq -c --arg did "$1" --arg tenant "$tenant" \
      'map(select(.identifier == $did)) | .[0] // empty | . + {tenantId: $tenant}')
    [[ -n "$profile" ]] && { printf '%s' "$profile"; return 0; }
  done
  return 0
}

# did:web -> URL of its DID document (http, per the environment's HTTP-only constraint)
did_document_url() { # <did>
  local rest="${1#did:web:}" authority path
  authority="${rest%%:*}"
  authority="${authority//%3A/:}"
  path="${rest#*:}"
  if [[ "$path" == "$rest" ]]; then
    echo "http://${authority}/.well-known/did.json"
  else
    echo "http://${authority}/${path//://}/did.json"
  fi
}

# poll <seconds> <interval> <description> <command...> — runs the command until it succeeds
poll() {
  local budget="$1" interval="$2" what="$3" start
  shift 3
  start=$(date +%s)
  until "$@"; do
    (( $(date +%s) - start < budget )) || die "timed out after ${budget}s waiting for $what"
    sleep "$interval"
  done
}
