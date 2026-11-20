#!/bin/bash

# End-to-end test of the Verification Environment: runs every script in sequence and fails on
# the first broken step.
#
#   1. install-ve.sh        — the VE on a single kind cluster (cxve / cxve.localhost on 80/443)
#   2. onboard-participant  — onboards a "Verification Participant" into EACH dataspace
#                             (DECADE-X and Catena-X) through the Membership Hub, gated on the
#                             membership reaching CREDENTIALS_OFFERED (exits non-zero on
#                             rejected/failed/stalled)
#   3. external DID check   — resolves each participant's DID document from OUTSIDE the cluster
#                             (plain HTTP from this host through the gateway), proving the VE's
#                             identity surface is reachable the way an external dataspace
#                             solution will reach it
#   4. gradle e2e suite     — the black-box tests in membership-hub/src/e2e-test against the VE,
#                             run once per dataspace (DecadeXE2eTest, CatenaXE2eTest): onboarding
#                             through the hub, data exchange, and the SCE certificate exchanges
#                             (notify + request of a held certificate, and a request fulfilled
#                             after issuance)
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
#                   (onboards additional participants, re-runs the DID checks)

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

# Explicit short names (unique per run) so each participant's DID is known up front — the
# external check below resolves exactly that DID. onboard-participant.sh would otherwise
# derive a random-suffixed name internally.
RUN_TS=$(date +%s)

for DATASPACE in decade-x catena-x; do
  # the short name forms the DID, which the hub deploys only once: keep them apart per dataspace
  SHORT_NAME="verification-participant-${DATASPACE%-x}-${RUN_TS}"
  PARTICIPANT_DID="did:web:identity.${HOST}:${SHORT_NAME}"

  step "Onboard the Verification Participant into ${DATASPACE} ($PARTICIPANT_DID)"
  FOLLOW=true ./scripts/onboard-participant.sh --dataspace "$DATASPACE" \
    --name "Verification Participant (${DATASPACE})" \
    --short-name "$SHORT_NAME" \
    --api-url "http://${HOST}/hub"

  step "Verify the ${DATASPACE} participant's DID resolves from OUTSIDE the cluster"
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
done

step "Run the Gradle e2e suite in DECADE-X and Catena-X (onboarding, data exchange, certificate exchange)"
# --rerun defeats Gradle's up-to-date check: the task's inputs don't change between runs, but
# the cluster state does. KUBECONFIG pins the suite's kubectl calls to this run's cluster.
KUBECONFIG="$HOME/.kube/${CLUSTER_NAME}.config" ./membership-hub/gradlew -p membership-hub e2eTest --rerun

step "RESULT"
echo "E2E PASSED in $(( ($(date +%s) - START) / 60 ))m $(( ($(date +%s) - START) % 60 ))s"
