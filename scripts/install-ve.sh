#!/bin/bash

# Stands up the complete Verification Environment (VE) on a kind cluster: ONE umbrella helm
# release (charts/cx-ve) containing the Core Platform Distribution, the Catena-X profile
# seeding, the Onboarding API, Certo and the Certo CFM agent. Run from the repository root.
#
# The VE is a SINGLE cluster: external dataspace solutions connect to it from outside, onboard
# a participant through the Onboarding API and exchange data with it. The HOSTNAME (-H) is the
# VE's identity domain — participant and issuer DIDs embed the gateway hostnames derived from
# it (did:web:identity.<host>:<participant>, did:web:issuer.<host>:issuer).
#
# All configuration is checked in statically in charts/cx-ve/values.yaml (defaults target
# cxve.localhost); all images are published — nothing is built or kind-loaded here. A
# non-default host is applied through the --set overrides assembled below.
#
# NOTE the release name cx-ve is load-bearing: the platform chart names its infra resources
# <release>-nats / <release>-vault / <release>-postgresql, and the umbrella values reference
# those names. NOTE also the namespace is fixed to edc-v: the CFM agents hardcode
# "system:serviceaccount:edc-v:…" client ids when registering jwtlet mappings during
# participant provisioning, so any other namespace breaks provisioning with 403s.
#
# Usage:
#   ./scripts/install-ve.sh [-c|--cluster <name>] [-H|--host <hostname>]
#                           [--http-port <port>] [--https-port <port>] [-h|--help]
#
#   -c, --cluster <name>      name of the kind cluster to (re)create (default: cxve). CAUTION:
#                             an existing cluster of that name is deleted first. The kubeconfig
#                             is written to ~/.kube/<name>.config
#   -H, --host <hostname>     HTTPRoute hostname the VE's APIs are exposed under, and the VE's
#                             identity domain — DIDs embed identity.<host> / issuer.<host>
#                             (default: cxve.localhost; *.localhost resolves to loopback on the
#                             host, and setup-did-dns.sh makes it resolve in-cluster)
#   --http-port <port>        host port mapped to the gateway's HTTP port 80 (default: 80)
#   --https-port <port>       host port mapped to the gateway's HTTPS port 443 (default: 443)
#   -h, --help                show usage and exit

set -euo pipefail

CLUSTER_NAME=cxve
# Fixed: the CFM agents hardcode system:serviceaccount:edc-v:… client ids (see header)
NAMESPACE=edc-v
# Fixed: the platform derives its infra resource names from the release name (see header)
RELEASE=cx-ve
UMBRELLA_CHART=charts/cx-ve
HOST=cxve.localhost
HTTP_PORT=80
HTTPS_PORT=443

usage() {
  cat <<EOF
Usage: $(basename "$0") [-c|--cluster <name>] [-H|--host <hostname>]
                        [--http-port <port>] [--https-port <port>] [-h|--help]

Options:
  -c, --cluster <name>     name of the kind cluster to (re)create (default: cxve).
                           CAUTION: an existing cluster of that name is deleted first.
                           The kubeconfig is written to ~/.kube/<name>.config
  -H, --host <hostname>    HTTPRoute hostname of the VE's APIs and its identity domain — DIDs
                           embed identity.<host> / issuer.<host> (default: cxve.localhost)
  --http-port <port>       host port mapped to the gateway's HTTP port (default: 80)
  --https-port <port>      host port mapped to the gateway's HTTPS port (default: 443)
  -h, --help               show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster|-H|--host|--http-port|--https-port)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -c|--cluster) CLUSTER_NAME="$2" ;;
        -H|--host) HOST="$2" ;;
        --http-port) HTTP_PORT="$2" ;;
        --https-port) HTTPS_PORT="$2" ;;
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

KUBECONFIG_FILE="$HOME/.kube/$CLUSTER_NAME.config"

# Everything host-derived in the checked-in values, always overridden from $HOST so the chosen
# host wins no matter what the values file says. The list is deliberately explicit — it
# documents exactly which values follow the host.
HOST_OVERRIDES=(
  --set "global.host=${HOST}"
  --set "catenax-profile.issuer.did=did:web:issuer.${HOST}:issuer"
  --set "onboarding-api.httpRoute.hostnames={${HOST}}"
  --set-string "onboarding-api.config.participant.did.template=did:web:identity.${HOST}:"
  --set "certo.gateway.hostnames={${HOST}}"
  --set "certo.sigletBaseUrl=http://${HOST}/api/siglet"
)

cleanup() {
  # kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE"
  echo "Cleanup complete"
}

# Generated infra config (kind cluster topology; all helm values are checked in)
GEN_DIR=$(mktemp -d)

# Trace the actual work (kept off during argument parsing / help output)
set -x

# KinD cluster config: maps the chosen host ports onto ports 80/443 of the (single) node
# container. Together with the hostPort settings in traefik-values.yaml this makes the gateway
# reachable on http://<host>:<http-port> without any kubectl port-forward.
# Note: port mappings are fixed at cluster creation - to change them, the cluster must be
# recreated.
cat > "$GEN_DIR/kind-config.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 80
        hostPort: ${HTTP_PORT}
        protocol: TCP
      - containerPort: 443
        hostPort: ${HTTPS_PORT}
        protocol: TCP
EOF

# Setup cluster
kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE" || true
kind create cluster -n "$CLUSTER_NAME" --config "$GEN_DIR/kind-config.yaml" --kubeconfig "$KUBECONFIG_FILE"
trap cleanup EXIT
export KUBECONFIG="$KUBECONFIG_FILE"

helm upgrade --install --namespace traefik traefik traefik/traefik --create-namespace -f traefik-values.yaml

kubectl rollout status deployment/traefik -n traefik --timeout=120s
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.5.1/standard-install.yaml

# CoreDNS pre-patch: the umbrella's seed hooks (profile seeding, certo activity) dereference
# gateway-hostname URLs while the install is still running, so the rewrites must exist BEFORE
# the release is applied. Hostnames are derived from the host here; the post-install run below
# replaces them with the list discovered from the actual HTTPRoutes.
"$(dirname "$0")/setup-did-dns.sh" --pre -c "$CLUSTER_NAME" -H "$HOST"

# Resolve the umbrella's dependencies (platform, catenax-profile, certo from OCI; the local
# onboarding-api chart is vendored from ../onboarding-api)
helm dependency update "$UMBRELLA_CHART"

# The whole VE as one release. Post-install hooks run all seeding in a single ordered hook
# space: platform seeds (weights 10/20) -> catenax-profile (110-130) -> onboarding-api jwtlet
# mapping (200) -> certo jwtlet mappings (210) -> certo activity/orchestration (220).
helm upgrade --install "$RELEASE" "$UMBRELLA_CHART" \
  --namespace "$NAMESPACE" --create-namespace \
  "${HOST_OVERRIDES[@]}" \
  --wait --timeout 20m

# Re-derive the CoreDNS rewrites from the deployed HTTPRoutes (replacing the pre-patch block)
# and verify: in-cluster DNS resolution plus the issuer DID document served through the
# gateway. A failure here stops the install rather than surfacing later as a
# credential-verification error during onboarding.
"$(dirname "$0")/setup-did-dns.sh" -c "$CLUSTER_NAME"
