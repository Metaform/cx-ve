#!/bin/bash

# Stands up the vendor stack — a system under test for the Verification Environment (VE) built
# from the VE's own components — on its OWN kind cluster: ONE helm release (vendor-stack/chart)
# with the Core Platform Distribution, the Catena-X profile seeding, Certo and the Certo CFM agent.
# Run from anywhere; paths are resolved relative to this script.
#
# The stack is a COUNTERPARTY of the VE, not part of it: the VE reaches it only over DSP, DCP and
# CCM, at the addresses its DID documents advertise. Those addresses carry the gateway port
# (http://vendor.localhost:8080, did:web:identity.vendor.localhost%3A8080:<participant>), which is
# what lets the same URL work from the host, from this cluster's pods and from the VE's pods — see
# chart/values.yaml. The VE's own cluster holds port 80, hence the default 8080 here.
#
# ALWAYS a fresh install: an existing cluster of the same name is deleted first. Upgrading the
# release in place is not supported — the catenax-profile tenant-manager seed is not idempotent and
# a second run duplicates the dataspace profile, which breaks participant provisioning.
#
# After the install the stack can reach nothing outside its cluster yet: run ./connect.sh to make
# the VE and this cluster resolve each other's hostnames (again after every re-install).
#
# Usage:
#   ./vendor-stack/install.sh [-c|--cluster <name>] [-H|--host <hostname>] [--http-port <port>]
#                             [--ve-issuer-did <did>] [-h|--help]
#
#   -c, --cluster <name>       kind cluster to (re)create (default: vendor). CAUTION: an existing
#                              cluster of that name is deleted first. The kubeconfig is written
#                              to ~/.kube/<name>.config
#   -H, --host <hostname>      the stack's gateway hostname and identity domain (default:
#                              vendor.localhost)
#   --http-port <port>         gateway port, on the host AND in every advertised URL and DID
#                              (default: 8080; must not collide with the VE's 80)
#   --ve-issuer-did <did>      the VE issuer this stack trusts (default:
#                              did:web:issuer.cxve.localhost:issuer)
#   -h, --help                 show usage and exit
#
# Requires: kind, kubectl, helm, docker

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CLUSTER_NAME=vendor
HOST=vendor.localhost
HTTP_PORT=8080
VE_ISSUER_DID="did:web:issuer.cxve.localhost:issuer"
# Fixed: the CFM agents hardcode system:serviceaccount:edc-v:… client ids
NAMESPACE=edc-v
# Fixed: the platform derives its infra resource names from the release name (see chart/values.yaml)
RELEASE=vendor-stack
CHART="$SCRIPT_DIR/chart"

usage() {
  cat <<EOF
Usage: $(basename "$0") [-c|--cluster <name>] [-H|--host <hostname>] [--http-port <port>]
                  [--ve-issuer-did <did>] [-h|--help]

Options:
  -c, --cluster <name>    kind cluster to (re)create (default: vendor). CAUTION: an existing
                          cluster of that name is deleted first
  -H, --host <hostname>   gateway hostname and identity domain (default: vendor.localhost)
  --http-port <port>      gateway port on the host and in advertised URLs/DIDs (default: 8080)
  --ve-issuer-did <did>   the VE issuer to trust (default: did:web:issuer.cxve.localhost:issuer)
  -h, --help              show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster|-H|--host|--http-port|--ve-issuer-did)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -c|--cluster) CLUSTER_NAME="$2" ;;
        -H|--host) HOST="$2" ;;
        --http-port) HTTP_PORT="$2" ;;
        --ve-issuer-did) VE_ISSUER_DID="$2" ;;
      esac
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ "$HTTP_PORT" == 80 ]]; then
  echo "Error: --http-port 80 is the VE's port; the vendor stack needs a different one" >&2
  exit 1
fi

KUBECONFIG_FILE="$HOME/.kube/$CLUSTER_NAME.config"

# Everything that follows the host and port. The issuer DID must equal what the platform derives
# (did:web:issuer.<host>%3A<port>:issuer) — it is pinned into the dataspace profile.
HOST_OVERRIDES=(
  --set "global.host=${HOST}"
  --set "global.external.port=${HTTP_PORT}"
  --set-string "catenax-profile.issuer.did=did:web:issuer.${HOST}%3A${HTTP_PORT}:issuer"
  --set "catenax-profile.issuer.trustedIssuers={${VE_ISSUER_DID}}"
  --set "certo.gateway.hostnames={${HOST}}"
)

GEN_DIR=$(mktemp -d)
trap 'rm -rf "$GEN_DIR"' EXIT

set -x

# The gateway port is published on the node container itself (Traefik hostPort) and mapped 1:1 to
# the host. Using the same number on both sides is what makes the node reachable from the VE's
# pods at <node IP>:<port> — the address connect.sh hands the VE's CoreDNS.
cat > "$GEN_DIR/kind-config.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: ${HTTP_PORT}
        hostPort: ${HTTP_PORT}
        protocol: TCP
EOF

kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE" || true
kind create cluster -n "$CLUSTER_NAME" --config "$GEN_DIR/kind-config.yaml" --kubeconfig "$KUBECONFIG_FILE"
export KUBECONFIG="$KUBECONFIG_FILE"

helm upgrade --install --namespace traefik traefik traefik/traefik --create-namespace \
  -f "$SCRIPT_DIR/traefik-values.yaml" \
  --set "ports.web.hostPort=${HTTP_PORT}" --set "ports.web.exposedPort=${HTTP_PORT}"
kubectl rollout status deployment/traefik -n traefik --timeout=120s
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.5.1/standard-install.yaml

# CoreDNS pre-patch: the release's seed hooks dereference gateway-hostname URLs while the install
# is still running, so this cluster's own hostnames must resolve before it starts.
"$REPO_ROOT/scripts/setup-did-dns.sh" --pre -c "$CLUSTER_NAME" -H "$HOST" -p "$HTTP_PORT"

helm dependency update "$CHART"

# Hook order in the release: platform seeds (10/20) -> catenax-profile (110-130) -> certo jwtlet
# mappings (210) -> certo activity + orchestration (220).
helm upgrade --install "$RELEASE" "$CHART" \
  --namespace "$NAMESPACE" --create-namespace \
  "${HOST_OVERRIDES[@]}" \
  --wait --timeout 20m

# Re-derive the rewrites from the deployed HTTPRoutes and verify the issuer DID document, served
# under the port-carrying DID, from inside the cluster.
"$REPO_ROOT/scripts/setup-did-dns.sh" -c "$CLUSTER_NAME" -p "$HTTP_PORT"

set +x
cat <<EOF

Vendor stack is up: cluster '$CLUSTER_NAME' (kubeconfig $KUBECONFIG_FILE), gateway http://${HOST}:${HTTP_PORT}
Next: ./vendor-stack/connect.sh, then ./vendor-stack/scripts/create-participant.sh
EOF
