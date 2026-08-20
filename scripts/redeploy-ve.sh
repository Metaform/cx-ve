#!/bin/bash

# Rebuilds everything the VE runs from this checkout and upgrades the running umbrella release in
# place: builds the Onboarding API, Compliance Tracker and Membership Hub images, loads them into
# the kind cluster, re-vendors the umbrella's chart dependencies (the onboarding-api and
# membership-hub charts are pulled from ../onboarding-api / ../membership-hub via file://, so
# local chart changes propagate) and runs `helm upgrade` with the same host overrides
# install-ve.sh applies.
#
# Use this for the edit-build-verify loop on an EXISTING cluster; it does not create or delete
# anything — for a cluster from scratch use install-ve.sh. The release's seed hooks re-run on
# upgrade; they are idempotent by design. Deployments running the locally built :latest images
# are restarted explicitly at the end: helm only rolls pods whose rendered spec changed, and a
# rebuilt image under an unchanged tag is invisible to it.
#
# Usage:
#   ./scripts/redeploy-ve.sh [-c|--cluster <name>] [-H|--host <hostname>] [-h|--help]
#
#   -c, --cluster <name>     kind cluster to load images into; its kubeconfig is expected at
#                            ~/.kube/<name>.config, as written by install-ve.sh (default: cxve);
#                            an explicitly set KUBECONFIG takes precedence
#   -H, --host <hostname>    HTTPRoute hostname / identity domain, must match what the cluster
#                            was installed with (default: cxve.localhost)

set -euo pipefail

CLUSTER_NAME=cxve
# Fixed, like in install-ve.sh: the release name and namespace are load-bearing (see its header)
NAMESPACE=edc-v
RELEASE=cx-ve
UMBRELLA_CHART=charts/cx-ve
HOST=cxve.localhost

usage() {
  cat <<EOF
Usage: $(basename "$0") [-c|--cluster <name>] [-H|--host <hostname>] [-h|--help]

Options:
  -c, --cluster <name>     kind cluster to load images into (default: cxve)
  -H, --host <hostname>    hostname the cluster was installed with (default: cxve.localhost)
  -h, --help               show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster|-H|--host)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -c|--cluster) CLUSTER_NAME="$2" ;;
        -H|--host) HOST="$2" ;;
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

# Default to the kind cluster's kubeconfig (as written by install-ve.sh) unless the caller set one
if [[ -z "${KUBECONFIG:-}" && -f "$HOME/.kube/$CLUSTER_NAME.config" ]]; then
  export KUBECONFIG="$HOME/.kube/$CLUSTER_NAME.config"
fi

# Same list as install-ve.sh: everything host-derived in the checked-in values. Must match the
# installed release or the upgrade would silently re-point DIDs and routes at another host.
HOST_OVERRIDES=(
  --set "global.host=${HOST}"
  --set "catenax-profile.issuer.did=did:web:issuer.${HOST}:issuer"
  --set "onboarding-api.httpRoute.hostnames={${HOST}}"
  --set-string "onboarding-api.config.participant.did.template=did:web:identity.${HOST}:"
  --set-string "onboarding-api.config.spring.security.oauth2.resourceserver.jwt.issuer-uri=http://${HOST}/auth/osp"
  --set-string "membership-hub.config.participant.did.template=did:web:identity.${HOST}:"
  --set "membership-hub.httpRoute.hostnames={${HOST}}"
  --set "certo.gateway.hostnames={${HOST}}"
)

# Image name -> build context. Both Dockerfiles COPY from their component directory (the same
# contexts .github/workflows/publish.yml builds from).
IMAGES=(
  "ghcr.io/metaform/cx-ve/onboardingapi:latest onboarding-api"
  "ghcr.io/metaform/cx-ve/compliance-tracker:latest compliance-tracker"
  "ghcr.io/metaform/cx-ve/membership-hub:latest membership-hub"
)

# Deployments running the images built above; restarted after the upgrade because a rebuilt
# image under an unchanged tag does not change the pod spec, so helm will not roll them.
DEPLOYMENTS=(cx-ve-onboarding-api compliance-tracker cx-ve-membership-hub)

set -x

for entry in "${IMAGES[@]}"; do
  read -r image context <<<"$entry"
  docker buildx build -t "$image" "$context"
  kind load docker-image "$image" -n "$CLUSTER_NAME"
done

helm dependency update "$UMBRELLA_CHART"

helm upgrade "$RELEASE" "$UMBRELLA_CHART" \
  --namespace "$NAMESPACE" \
  "${HOST_OVERRIDES[@]}" \
  --wait --timeout 20m

for deployment in "${DEPLOYMENTS[@]}"; do
  kubectl rollout restart "deployment/$deployment" -n "$NAMESPACE"
done
for deployment in "${DEPLOYMENTS[@]}"; do
  kubectl rollout status "deployment/$deployment" -n "$NAMESPACE" --timeout=300s
done

set +x
echo "Redeployed release $RELEASE from this checkout (host $HOST, cluster $CLUSTER_NAME)."
