#!/bin/bash

# Stands up a complete local environment — Core Platform Distribution, Catena-X profile and
# Onboarding API — on a kind cluster. Run from the repository root.
#
# Usage:
#   ./scripts/install-ve.sh [cluster-name]
#
#   cluster-name  name of the kind cluster to (re)create (default: cxve). CAUTION: an existing
#                 cluster of that name is deleted first. The kubeconfig is written to
#                 ~/.kube/<cluster-name>.config

set -euxo pipefail

CLUSTER_NAME="${1:-cxve}"
NAMESPACE=edc-v
KUBECONFIG_FILE="$HOME/.kube/$CLUSTER_NAME.config"

# Helm chart of the core-platform-distribution
CORE_CHART="${CORE_CHART:-oci://ghcr.io/eclipse-cfm/charts/core-platform-distribution}"
CORE_CHART_VERSION=0.0.13

# Helm chart of the Catena-X Profile
CXPROF_CHART="${CXPROF_CHART:-oci://ghcr.io/metaform/charts/catenax-profile}"
CXPROF_CHART_VERSION=0.0.2

# Helm chart of the Onboarding API
OBAPI_CHART="${OBAPI_CHART:-oci://ghcr.io/metaform/charts/cx-ve}"
OBAPI_CHART_VERSION=0.0.1

# Always tear down the cluster on exit, whether the run succeeds, fails on any
# command (set -e), or is interrupted.
cleanup() {
  # kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE"
  echo "Cleanup complete"
}

# Setup cluster. kind-config.yaml maps host ports 80/443 into the node container; together
# with the Traefik hostPort settings in values.yaml, the gateway is reachable on
# http://localhost without a port-forward.
kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE" || true
kind create cluster -n "$CLUSTER_NAME" --config kind-config.yaml --kubeconfig "$KUBECONFIG_FILE"
trap cleanup EXIT
export KUBECONFIG="$KUBECONFIG_FILE"

helm upgrade --install --namespace traefik traefik traefik/traefik --create-namespace -f traefik-values.yaml

kubectl rollout status deployment/traefik -n traefik --timeout=120s
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.5.1/standard-install.yaml


# Deploy the Core Platform Distribution (connector, identity hub, issuer service, siglet, CFM,
# jwtlet/clearglass, gateway, infra + generic platform seeding).
# OCI/registry reference: the published chart bundles its sub-chart dependencies.
helm upgrade --install core-platform "$CORE_CHART" \
  --namespace "$NAMESPACE" --create-namespace \
  -f platform-override-values.yaml \
  --version $CORE_CHART_VERSION \
  --wait --timeout 15m


# Deploy the CX Profile chart
helm upgrade --install cx-profile "$CXPROF_CHART" \
  --namespace "$NAMESPACE" \
  --version "$CXPROF_CHART_VERSION" \
  --wait

docker buildx build -f Dockerfile -t ghcr.io/metaform/cx-ve/onboardingapi:latest .
kind load docker-image ghcr.io/metaform/cx-ve/onboardingapi:latest -n $CLUSTER_NAME

# Deploy the Onboarding API application (app only — the bundled platform dependencies stay
# disabled; the platform was installed as its own release above)
helm upgrade --install obapi "$OBAPI_CHART" \
  --namespace "$NAMESPACE" --create-namespace \
  --version "$OBAPI_CHART_VERSION" \
  --wait
