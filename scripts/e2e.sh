#!/bin/bash

# End-to-end test of the dual-VE setup: runs every script in sequence and fails on the first
# broken step.
#
#   1. install-ve.sh        — two kind clusters (ve1/ve1.local on 80/443, ve2/ve2.local on
#                             8081/8444 with distinct pod/service subnets)
#   2. onboard-participant  — one participant per VE, gated on the onboarding reaching
#                             COMPLETED (exits non-zero on rejected/failed/stalled)
#   3. connect-ves.sh       — routes, DNS zone forwarding, mutual issuer trust
#   4. dsp-tests.sh         — cross-VE DSP exchange driven from the host through each VE's
#                             gateway (catalog, negotiation to FINALIZED, transfer to STARTED,
#                             EDR retrieval + payload download)
#
# On success the clusters are left running for inspection; remove them with
#   kind delete cluster -n ve1 && kind delete cluster -n ve2
# On FAILURE both clusters are deleted, so a broken attempt doesn't leave two large
# half-configured clusters behind — except when --skip-install was used (the clusters existed
# before this run) or E2E_KEEP_CLUSTERS=true (e.g. CI, which exports the cluster logs after a
# failure and discards the runner anyway).
#
# Usage:
#   ./scripts/e2e.sh [--skip-install] [-h|--help]
#
#   --skip-install  reuse the existing ve1/ve2 clusters instead of recreating them
#                   (onboards additional participants, reconnects, re-runs the demo)

set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_INSTALL=false

usage() {
  cat <<EOF
Usage: $(basename "$0") [--skip-install] [-h|--help]

Options:
  --skip-install  reuse existing ve1/ve2 clusters (skips the ~30 min install phase)
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

# The dual-VE setup requires the working-tree app chart
export OBAPI_CHART="${OBAPI_CHART:-charts/cx-ve}"

# Tear down the clusters when the run fails (see header for the exceptions)
cleanup() {
  local ec=$?
  if [[ $ec -ne 0 && "$SKIP_INSTALL" != "true" && "${E2E_KEEP_CLUSTERS:-false}" != "true" ]]; then
    echo ""
    echo "E2E FAILED (exit $ec) — deleting clusters ve1 and ve2"
    kind delete cluster -n ve1 || true
    kind delete cluster -n ve2 || true
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
  step "Install VE1 (cluster ve1, ve1.local, ports 80/443)"
  ./scripts/install-ve.sh -c ve1 -d ve1.local -H ve1.localhost

  step "Install VE2 (cluster ve2, ve2.local, ports 8081/8444)"
  ./scripts/install-ve.sh -c ve2 -d ve2.local -H ve2.localhost \
    --http-port 8081 --https-port 8444 \
    --pod-subnet 10.245.0.0/16 --service-subnet 10.97.0.0/16
fi

step "Onboard participant in VE1"
FOLLOW=true ./scripts/onboard-participant.sh --name "Alpha Manufacturing" \
  --cluster ve1 --namespace edc-v --api-url http://ve1.localhost/onboarding

step "Onboard participant in VE2"
FOLLOW=true ./scripts/onboard-participant.sh --name "Beta Logistics" \
  --cluster ve2 --namespace edc-v --api-url http://ve2.localhost:8081/onboarding

step "Connect the VEs (routes, DNS, mutual issuer trust)"
./scripts/connect-ves.sh

step "Cross-VE DSP exchange (host-driven via the gateways)"
./scripts/dsp-tests.sh

step "RESULT"
echo "E2E PASSED in $(( ($(date +%s) - START) / 60 ))m $(( ($(date +%s) - START) % 60 ))s"
