#!/bin/bash

# End-to-end test of the Verification Environment: runs every script in sequence and fails on
# the first broken step.
#
#   1. install-ve.sh        — the VE on a single kind cluster (cxve / cxve.localhost on 80/443)
#   2. onboard-participant  — onboards the "Verification Participant", gated on the onboarding
#                             reaching COMPLETED (exits non-zero on rejected/failed/stalled)
#   3. external DID check   — resolves the participant's DID document from OUTSIDE the cluster
#                             (plain HTTP from this host through the gateway), proving the VE's
#                             identity surface is reachable the way an external dataspace
#                             solution will reach it
#   4. gradle e2e suite     — the black-box tests in onboarding-api/src/e2e-test against the VE:
#                             onboarding callback, credential-gated data exchange, and the
#                             CX-0135 Flow B certificate exchange
#
# On success the cluster is left running for inspection; remove it with
#   kind delete cluster -n cxve
# On FAILURE the cluster is deleted, so a broken attempt doesn't leave a large half-configured
# cluster behind — except when --skip-install was used (the cluster existed before this run) or
# E2E_KEEP_CLUSTERS=true (e.g. CI, which exports the cluster logs after a failure and discards
# the runner anyway).
#
# Usage:
#   ./scripts/e2e.sh [--skip-install] [-h|--help]
#
#   --skip-install  reuse the existing cxve cluster instead of recreating it
#                   (onboards an additional participant, re-runs the DID check)

set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER_NAME=cxve
HOST=cxve.localhost
SKIP_INSTALL=false

usage() {
  cat <<EOF
Usage: $(basename "$0") [--skip-install] [-h|--help]

Options:
  --skip-install  reuse the existing cxve cluster (skips the ~15 min install phase)
  -h, --help      show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-install) SKIP_INSTALL=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

# Tear down the cluster when the run fails (see header for the exceptions)
cleanup() {
  local ec=$?
  if [[ $ec -ne 0 && "$SKIP_INSTALL" != "true" && "${E2E_KEEP_CLUSTERS:-false}" != "true" ]]; then
    echo ""
    echo "E2E FAILED (exit $ec) — deleting cluster $CLUSTER_NAME"
    kind delete cluster -n "$CLUSTER_NAME" || true
  fi
}
trap cleanup EXIT

START=$(date +%s)
step() {
  echo ""
  echo "======================================================================"
  echo "== $* (t+$(( ($(date +%s) - START) / 60 ))m)"
  echo "======================================================================"
}

if [[ "$SKIP_INSTALL" != "true" ]]; then
  step "Install the VE (cluster $CLUSTER_NAME, $HOST, ports 80/443)"
  ./scripts/install-ve.sh -c "$CLUSTER_NAME" -H "$HOST"
fi

# Explicit short name (unique per run) so the participant's DID is known up front — the
# external check below resolves exactly this DID. onboard-participant.sh would otherwise
# derive a random-suffixed name internally.
SHORT_NAME="verification-participant-$(date +%s)"
PARTICIPANT_DID="did:web:identity.${HOST}:${SHORT_NAME}"

step "Onboard the Verification Participant ($PARTICIPANT_DID)"
FOLLOW=true ./scripts/onboard-participant.sh --name "Verification Participant" \
  --short-name "$SHORT_NAME" \
  --cluster "$CLUSTER_NAME" --namespace edc-v --api-url "http://${HOST}/onboarding"

step "Verify the participant DID resolves from OUTSIDE the cluster"
# Resolution exactly as an external dataspace solution performs it: did:web -> plain HTTP GET
# of the DID document, from this host, through the gateway. identity.<host> is a *.localhost
# name, so it reaches 127.0.0.1:80 -> the kind host-port mapping -> Traefik -> IdentityHub.
# curl is fine HERE (unlike in the in-cluster probes): its internal *.localhost->loopback
# short-circuit lands exactly where the gateway lives from the host's point of view.
DID_URL="http://identity.${HOST}/${SHORT_NAME}/did.json"
echo "GET $DID_URL"
BODY=$(curl -sf --max-time 30 "$DID_URL")
if printf '%s' "$BODY" | grep -qF "\"id\":\"${PARTICIPANT_DID}\""; then
  echo "OK: DID document served, id matches ${PARTICIPANT_DID}"
else
  echo "ERROR: DID document does not carry the expected id ${PARTICIPANT_DID}:" >&2
  printf '%s\n' "$BODY" >&2
  exit 1
fi

step "Run the Gradle e2e suite (onboarding, data exchange, certificate exchange)"
# The suite's registration-status callbacks are POSTed from in-cluster pods to a WireMock
# server on THIS host. Its default callback hostname, host.docker.internal, only exists on
# Docker Desktop (macOS/Windows); on plain Linux Docker (e.g. a CI runner) pods cannot resolve
# it (UnresolvedAddressException). Derive the address the kind node reaches the host under —
# its default gateway, i.e. the host side of the kind bridge — and hand it to the suite.
if [[ "$(uname -s)" == "Linux" ]]; then
  # NOTE the awk consumes ALL input on purpose (first match remembered, printed at END): an
  # early `exit` would close the pipe while docker exec is still writing -> SIGPIPE -> the
  # whole pipeline fails with 141 under pipefail.
  E2E_CALLBACK_HOST=$(docker exec "${CLUSTER_NAME}-control-plane" ip route \
    | awk '$1 == "default" && !gw {gw = $3} END {print gw}')
  if [[ -z "$E2E_CALLBACK_HOST" ]]; then
    echo "ERROR: could not derive the host address from ${CLUSTER_NAME}-control-plane's default route" >&2
    exit 1
  fi
  export E2E_CALLBACK_HOST
  echo "Linux host: in-cluster callbacks will target ${E2E_CALLBACK_HOST} (kind node default gateway)"
fi
# --rerun defeats Gradle's up-to-date check: the task's inputs don't change between runs, but
# the cluster state does. KUBECONFIG pins the suite's kubectl calls to this run's cluster.
KUBECONFIG="$HOME/.kube/${CLUSTER_NAME}.config" ./onboarding-api/gradlew -p onboarding-api e2eTest --rerun

step "RESULT"
echo "E2E PASSED in $(( ($(date +%s) - START) / 60 ))m $(( ($(date +%s) - START) % 60 ))s"
