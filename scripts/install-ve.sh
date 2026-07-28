#!/bin/bash

# Stands up a complete local environment — Core Platform Distribution, Catena-X profile and
# Onboarding API — on a kind cluster. Run from the repository root.
#
# Multiple VEs can coexist on one host: give each its own cluster name, DNS domain, hostname,
# host ports and (for later cross-cluster routing) pod/service subnets. The HOSTNAME (-H) is the
# VE's identity domain — participant and issuer DIDs embed the gateway hostnames derived from it
# (did:web:identity.<host>:<participant>, did:web:issuer.<host>:issuer), so two VEs MUST use
# distinct hostnames to mint distinct DIDs. Distinct DNS DOMAINS (-d) are still required, but
# for cross-cluster DNS (connect-ves.sh forwards the peer's zone to the peer's kube-dns), e.g.:
#
#   ./scripts/install-ve.sh -c ve1 -d ve1.local -H ve1.localhost
#   ./scripts/install-ve.sh -c ve2 -d ve2.local -H ve2.localhost --http-port 8081 --https-port 8444 \
#       --pod-subnet 10.245.0.0/16 --service-subnet 10.97.0.0/16
#
# NOTE the namespace is fixed to edc-v: the CFM agents hardcode
# "system:serviceaccount:edc-v:…" client ids when registering jwtlet mappings during
# participant provisioning, so any other namespace breaks provisioning with 403s.
#
# Usage:
#   ./scripts/install-ve.sh [-c|--cluster <name>] [-d|--dns-domain <domain>] [-H|--host <hostname>]
#                           [--http-port <port>] [--https-port <port>]
#                           [--pod-subnet <cidr>] [--service-subnet <cidr>] [-h|--help]
#
#   -c, --cluster <name>      name of the kind cluster to (re)create (default: cxve). CAUTION:
#                             an existing cluster of that name is deleted first. The kubeconfig
#                             is written to ~/.kube/<name>.config
#   -d, --dns-domain <dom>    cluster DNS domain (default: cluster.local). Distinct per VE for
#                             cross-cluster DNS (connect-ves.sh zone forwarding)
#   -H, --host <hostname>     HTTPRoute hostname the VE's APIs are exposed under, and the VE's
#                             identity domain — DIDs embed identity.<host> / issuer.<host>
#                             (default: cxve.localhost; *.localhost resolves to loopback on the
#                             host, and setup-did-dns.sh makes it resolve in-cluster)
#   --http-port <port>        host port mapped to the gateway's HTTP port 80 (default: 80)
#   --https-port <port>       host port mapped to the gateway's HTTPS port 443 (default: 443)
#   --pod-subnet <cidr>       pod CIDR (default: 10.244.0.0/16); distinct per VE to allow
#                             cross-cluster routing later
#   --service-subnet <cidr>   service CIDR (default: 10.96.0.0/16); distinct per VE likewise
#   -h, --help                show usage and exit

set -euo pipefail

CLUSTER_NAME=cxve
# Fixed: the CFM agents hardcode system:serviceaccount:edc-v:… client ids (see header)
NAMESPACE=edc-v
DNS_DOMAIN=cluster.local
HOST=cxve.localhost
HTTP_PORT=80
HTTPS_PORT=443
POD_SUBNET=10.244.0.0/16
SERVICE_SUBNET=10.96.0.0/16

usage() {
  cat <<EOF
Usage: $(basename "$0") [-c|--cluster <name>] [-d|--dns-domain <domain>] [-H|--host <hostname>]
                        [--http-port <port>] [--https-port <port>]
                        [--pod-subnet <cidr>] [--service-subnet <cidr>] [-h|--help]

Options:
  -c, --cluster <name>     name of the kind cluster to (re)create (default: cxve).
                           CAUTION: an existing cluster of that name is deleted first.
                           The kubeconfig is written to ~/.kube/<name>.config
  -d, --dns-domain <dom>   cluster DNS domain (default: cluster.local). Distinct per VE for
                           cross-cluster DNS (connect-ves.sh zone forwarding).
  -H, --host <hostname>    HTTPRoute hostname of the VE's APIs and its identity domain — DIDs
                           embed identity.<host> / issuer.<host> (default: cxve.localhost)
  --http-port <port>       host port mapped to the gateway's HTTP port (default: 80)
  --https-port <port>      host port mapped to the gateway's HTTPS port (default: 443)
  --pod-subnet <cidr>      pod CIDR (default: 10.244.0.0/16)
  --service-subnet <cidr>  service CIDR (default: 10.96.0.0/16)
  -h, --help               show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster|-d|--dns-domain|-H|--host|--http-port|--https-port|--pod-subnet|--service-subnet)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -c|--cluster) CLUSTER_NAME="$2" ;;
        -d|--dns-domain) DNS_DOMAIN="$2" ;;
        -H|--host) HOST="$2" ;;
        --http-port) HTTP_PORT="$2" ;;
        --https-port) HTTPS_PORT="$2" ;;
        --pod-subnet) POD_SUBNET="$2" ;;
        --service-subnet) SERVICE_SUBNET="$2" ;;
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

# Helm chart of the core-platform-distribution
CORE_CHART="${CORE_CHART:-oci://ghcr.io/eclipse-cfm/charts/core-platform-distribution}"
CORE_CHART_VERSION=0.0.17

# Helm chart of the Catena-X Profile
CXPROF_CHART="${CXPROF_CHART:-oci://ghcr.io/metaform/charts/catenax-profile}"
CXPROF_CHART_VERSION=0.0.5

# Helm chart of the Onboarding API. Defaults to the working-tree chart: the script always
# builds and loads the working-tree IMAGE anyway, so deploying the published chart with it
# would mix versions. Set OBAPI_CHART=oci://ghcr.io/metaform/charts/cx-ve for the published
# one (--version below only applies to such remote refs; helm ignores it for a directory).
OBAPI_CHART="${OBAPI_CHART:-charts/cx-ve}"
OBAPI_CHART_VERSION=0.0.1

# Always tear down the cluster on exit, whether the run succeeds, fails on any
# command (set -e), or is interrupted.
cleanup() {
  # kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE"
  echo "Cleanup complete"
}

# Per-VE config files generated below (kind cluster config, app value overrides)
GEN_DIR=$(mktemp -d)

# Trace the actual work (kept off during argument parsing / help output)
set -x

# KinD cluster config: maps the chosen host ports onto ports 80/443 of the (single) node
# container. Together with the hostPort settings in traefik-values.yaml this makes the gateway
# reachable on http://<host>:<http-port> without any kubectl port-forward.
# Note: port mappings, DNS domain and subnets are fixed at cluster creation - to change them,
# the cluster must be recreated.
#
# The service-account issuer is pinned to the cluster.local default: jwtlet's chart config
# hardcodes cluster_issuer/client_audience to https://kubernetes.default.svc.cluster.local, and
# with a custom dnsDomain kubeadm would otherwise derive a different issuer, breaking all
# token exchange.
cat > "$GEN_DIR/kind-config.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  podSubnet: ${POD_SUBNET}
  serviceSubnet: ${SERVICE_SUBNET}
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 80
        hostPort: ${HTTP_PORT}
        protocol: TCP
      - containerPort: 443
        hostPort: ${HTTPS_PORT}
        protocol: TCP
kubeadmConfigPatches:
  - |
    kind: ClusterConfiguration
    networking:
      dnsDomain: ${DNS_DOMAIN}
    apiServer:
      extraArgs:
        service-account-issuer: https://kubernetes.default.svc.cluster.local
EOF

# Overrides for the Onboarding API chart: every in-cluster URL and the DID template embed the
# VE's DNS domain; the HTTPRoute hostname is the per-VE host.
cat > "$GEN_DIR/obapi-values.yaml" <<EOF
jwtlet:
  healthUrl: http://jwtlet.${NAMESPACE}.svc.${DNS_DOMAIN}:8080/health
  mgmtUrl: http://jwtlet.${NAMESPACE}.svc.${DNS_DOMAIN}:8081/api/v1
natsAuth:
  vaultUrl: http://core-platform-vault.${NAMESPACE}.svc.${DNS_DOMAIN}:8200
  seed:
    platformNamespace: ${NAMESPACE}
httpRoute:
  hostnames:
    - ${HOST}
config:
  nats:
    url: nats://core-platform-nats.${NAMESPACE}.svc.${DNS_DOMAIN}:4222
  token:
    exchange:
      url: http://jwtlet.${NAMESPACE}.svc.${DNS_DOMAIN}:8080
  tenant-manager:
    url: http://tenant-manager.${NAMESPACE}.svc.${DNS_DOMAIN}:8080/api/v1alpha1
  identityhub:
    url: http://identityhub.${NAMESPACE}.svc.${DNS_DOMAIN}:7081/api/identity/v1beta
  participant:
    did:
      # Must match the platform's IdentityHub did:web hostname (identity.<host>), since
      # IdentityHub resolves a DID document by the request URL — a participant DID minted
      # under any other authority will not resolve through the gateway.
      template: "did:web:identity.${HOST}:"
    dataplane:
      # Demo data source served for HttpData-PULL transfers: a public sample-JSON API, so the
      # demo payload is unambiguous sample data rather than platform infrastructure
      endpoint: https://jsonplaceholder.typicode.com/todos/1
EOF

# Setup cluster
kind delete cluster -n "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE" || true
kind create cluster -n "$CLUSTER_NAME" --config "$GEN_DIR/kind-config.yaml" --kubeconfig "$KUBECONFIG_FILE"
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
  --set global.host="$HOST" \
  --set global.namespace="$NAMESPACE" \
  --set global.clusterDomain="svc.${DNS_DOMAIN}" \
  --version $CORE_CHART_VERSION \
  --wait --timeout 15m

# Make the platform's gateway hostnames resolvable from inside the cluster. The platform
# advertises itself under them (DSP callback, credential service, issuance, did:web), and the
# runtimes dereference those URLs themselves — without this, a *.localhost name resolves to the
# pod's own loopback and every DID lookup fails. Must run after the platform install, since the
# hostname list is read off the deployed HTTPRoutes; also verifies the result, so a failure here
# stops the install rather than surfacing later as a credential-verification error.
"$(dirname "$0")/setup-did-dns.sh" -c "$CLUSTER_NAME"


# Deploy the CX Profile chart (global.namespace/clusterDomain tell its seed jobs where the
# platform lives). issuer.did must equal the DID the platform minted for the issuer participant
# context — it is pinned into the dataspace profile's credentialSpecs, and a mismatch only
# surfaces at credential verification during onboarding, far from the cause. The platform derives
# that DID from its gateway hostname as did:web:issuer.<host>:issuer unless it was pinned there
# via edc.issuerservice.did.id; read the live value back with
#   kubectl -n "$NAMESPACE" get httproute issuerservice-did -o jsonpath='{.spec.hostnames[0]}'
helm upgrade --install cx-profile "$CXPROF_CHART" \
  --namespace "$NAMESPACE" \
  --set global.namespace="$NAMESPACE" \
  --set global.clusterDomain="svc.${DNS_DOMAIN}" \
  --set issuer.did="did:web:issuer.${HOST}:issuer" \
  --version "$CXPROF_CHART_VERSION" \
  --wait

docker buildx build -f Dockerfile -t ghcr.io/metaform/cx-ve/onboardingapi:latest .
kind load docker-image ghcr.io/metaform/cx-ve/onboardingapi:latest -n "$CLUSTER_NAME"

# Deploy the Onboarding API application (app only — the platform was installed as its own
# release above)
helm upgrade --install obapi "$OBAPI_CHART" \
  --namespace "$NAMESPACE" --create-namespace \
  -f "$GEN_DIR/obapi-values.yaml" \
  --version "$OBAPI_CHART_VERSION" \
  --wait
