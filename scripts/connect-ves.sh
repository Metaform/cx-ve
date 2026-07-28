#!/bin/bash

# Connects two locally installed VEs (see install-ve.sh) so their participants can resolve each
# other's DIDs and verify each other's credentials — the prerequisite for cross-VE data exchange:
#
#   1. Static routes between the two kind node containers (they share the docker "kind"
#      network), covering each peer's pod and service subnets.
#   2. CoreDNS zone forwarding: each cluster forwards the peer's DNS domain (e.g. ve2.local)
#      to the peer's kube-dns service, making <svc>.edc-v.svc.<peer-domain> names resolve
#      cluster-to-cluster.
#   3. Peer gateway-hostname DNS (setup-did-dns.sh --peer): each cluster forwards the peer's
#      HTTPRoute hostnames (ve2.localhost, issuer.ve2.localhost, identity.ve2.localhost) to the
#      peer's kube-dns. Everything a VE advertises about itself — issuer and participant DIDs,
#      the DSP callback, the CredentialService endpoint — lives under those hostnames, and the
#      runtimes dereference them on every cross-VE interaction. Includes its own verification
#      (DNS + an end-to-end fetch of the peer issuer's DID document).
#   4. Mutual trust: each VE's controlplane gets the peer's issuer DID
#      (did:web:issuer.<peer-host>:issuer) added to its edc.controlplane.trustedIssuers via helm
#      upgrade. Because a platform upgrade regenerates the NATS users.conf (dropping the
#      Onboarding API's NKey entry), the obapi release is upgraded right after to re-merge it,
#      and the obapi deployment is restarted.
#
# Assumes the two VEs were installed with the conventions of install-ve.sh, e.g.:
#   ./scripts/install-ve.sh -c ve1 -d ve1.local -H ve1.localhost
#   ./scripts/install-ve.sh -c ve2 -d ve2.local -H ve2.localhost --http-port 8081 \
#       --https-port 8444 --pod-subnet 10.245.0.0/16 --service-subnet 10.97.0.0/16
#
# Usage:
#   ./scripts/connect-ves.sh [--c1 <cluster>] [--d1 <domain>] [--h1 <host>]
#                            [--c2 <cluster>] [--d2 <domain>] [--h2 <host>] [-h|--help]
#
# NOTE the static routes do not survive a restart of the kind node containers — re-run this
# script after a docker restart.

set -euo pipefail

C1=ve1; D1=ve1.local; H1=ve1.localhost
C2=ve2; D2=ve2.local; H2=ve2.localhost
NAMESPACE=edc-v

# Charts (must match what the VEs were installed with)
CORE_CHART="${CORE_CHART:-oci://ghcr.io/eclipse-cfm/charts/core-platform-distribution}"
CORE_CHART_VERSION=0.0.15
# The dual-VE setup requires the working-tree app chart (see install-ve.sh)
OBAPI_CHART="${OBAPI_CHART:-charts/cx-ve}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--c1 <cluster>] [--d1 <domain>] [--h1 <host>]
                        [--c2 <cluster>] [--d2 <domain>] [--h2 <host>] [-h|--help]

Options (defaults match the dual-VE install convention):
  --c1/--c2 <cluster>  kind cluster names            (default: ve1 / ve2)
  --d1/--d2 <domain>   cluster DNS domains           (default: ve1.local / ve2.local)
  --h1/--h2 <host>     gateway HTTPRoute hostnames   (default: ve1.localhost / ve2.localhost)
  -h, --help           show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --c1|--d1|--h1|--c2|--d2|--h2)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        --c1) C1="$2" ;; --d1) D1="$2" ;; --h1) H1="$2" ;;
        --c2) C2="$2" ;; --d2) D2="$2" ;; --h2) H2="$2" ;;
      esac
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

GEN_DIR=$(mktemp -d)

kubeconfig() { echo "$HOME/.kube/$1.config"; }
node_of() { echo "$1-control-plane"; }

node_ip() {
  docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$(node_of "$1")"
}

# Cluster-level pod/service CIDRs from the kubeadm config
subnets_of() { # <cluster> -> "podSubnet serviceSubnet"
  kubectl --kubeconfig "$(kubeconfig "$1")" get cm kubeadm-config -n kube-system -o yaml \
    | awk '/podSubnet:/{p=$2} /serviceSubnet:/{s=$2} END{print p, s}'
}

dns_ip_of() {
  kubectl --kubeconfig "$(kubeconfig "$1")" get svc kube-dns -n kube-system \
    -o jsonpath='{.spec.clusterIP}'
}

# ---- 1. Static routes between the node containers -------------------------------------------
add_routes() { # <from-cluster> <to-cluster>
  local via; via=$(node_ip "$2")
  local pod svc; read -r pod svc <<< "$(subnets_of "$2")"
  echo ">> $1: route $pod and $svc via $via"
  docker exec "$(node_of "$1")" ip route replace "$pod" via "$via"
  docker exec "$(node_of "$1")" ip route replace "$svc" via "$via"
}

# ---- 2. CoreDNS zone forwarding -------------------------------------------------------------
forward_dns() { # <cluster> <peer-domain> <peer-dns-ip>
  local kc; kc=$(kubeconfig "$1")
  local corefile; corefile=$(kubectl --kubeconfig "$kc" get cm coredns -n kube-system -o jsonpath='{.data.Corefile}')
  if grep -q "^$2:53" <<< "$corefile"; then
    echo ">> $1: forward zone $2 already present"
    return
  fi
  echo ">> $1: forwarding zone $2 to $3"
  printf '%s\n%s:53 {\n    errors\n    cache 30\n    forward . %s\n}\n' "$corefile" "$2" "$3" > "$GEN_DIR/Corefile.$1"
  kubectl --kubeconfig "$kc" create configmap coredns -n kube-system \
    --from-file=Corefile="$GEN_DIR/Corefile.$1" --dry-run=client -o yaml \
    | kubectl --kubeconfig "$kc" apply -f -
  kubectl --kubeconfig "$kc" rollout restart deployment/coredns -n kube-system
  kubectl --kubeconfig "$kc" rollout status deployment/coredns -n kube-system --timeout=120s
}

# ---- 4. Mutual issuer trust -----------------------------------------------------------------
trust_peer() { # <cluster> <own-domain> <own-host> <peer-cluster> <peer-domain> <peer-host>
  local kc; kc=$(kubeconfig "$1")
  export KUBECONFIG="$kc"

  # Full trustedIssuers list: this file wins over platform-override-values.yaml, so it must
  # repeat the cxtck entry alongside the peer issuer. The peer issuer DID is the one the peer
  # platform minted from its gateway hostname (did:web:issuer.<peer-host>:issuer) — resolvable
  # here thanks to the peer gateway-hostname forwarding in step 3.
  cat > "$GEN_DIR/trust.$1.yaml" <<EOF
edc:
  controlplane:
    trustedIssuers:
      - name: cxtck
        id: "did:web:cx-tck.edc-v.svc.cluster.local:issuer"
      - name: $4
        id: "did:web:issuer.$6:issuer"
EOF

  echo ">> $1: trusting issuer of $4 (did:web:issuer.$6:issuer)"
  helm upgrade --install core-platform "$CORE_CHART" \
    --namespace "$NAMESPACE" \
    -f platform-override-values.yaml \
    -f "$GEN_DIR/trust.$1.yaml" \
    --set global.host="$3" \
    --set global.namespace="$NAMESPACE" \
    --set global.clusterDomain="svc.$2" \
    --version $CORE_CHART_VERSION \
    --wait --timeout 15m

  # The platform upgrade regenerated the NATS users.conf and dropped the obapi NKey entry;
  # re-run the obapi release to re-merge it, then restart obapi so its NATS listener
  # re-subscribes cleanly.
  cat > "$GEN_DIR/obapi.$1.yaml" <<EOF
jwtlet:
  healthUrl: http://jwtlet.${NAMESPACE}.svc.$2:8080/health
  mgmtUrl: http://jwtlet.${NAMESPACE}.svc.$2:8081/api/v1
natsAuth:
  vaultUrl: http://core-platform-vault.${NAMESPACE}.svc.$2:8200
  seed:
    platformNamespace: ${NAMESPACE}
httpRoute:
  hostnames:
    - $3
config:
  nats:
    url: nats://core-platform-nats.${NAMESPACE}.svc.$2:4222
  token:
    exchange:
      url: http://jwtlet.${NAMESPACE}.svc.$2:8080
  tenant-manager:
    url: http://tenant-manager.${NAMESPACE}.svc.$2:8080/api/v1alpha1
  identityhub:
    url: http://identityhub.${NAMESPACE}.svc.$2:7081/api/identity/v1beta
  participant:
    did:
      # Keep in sync with install-ve.sh: must match the platform's IdentityHub did:web
      # hostname (identity.<host>), or participant DIDs will not resolve through the gateway.
      template: "did:web:identity.$3:"
    dataplane:
      # Keep in sync with install-ve.sh: demo data source for HttpData-PULL transfers
      endpoint: https://jsonplaceholder.typicode.com/todos/1
EOF
  helm upgrade --install obapi "$OBAPI_CHART" \
    --namespace "$NAMESPACE" \
    -f "$GEN_DIR/obapi.$1.yaml" \
    --wait
  kubectl rollout restart deployment/obapi-cx-ve -n "$NAMESPACE"
  kubectl rollout status deployment/obapi-cx-ve -n "$NAMESPACE" --timeout=420s

  # The chart's trustedIssuers hook only registers the issuer *id*; the DCP verifier
  # additionally requires the credential types the issuer may issue, or presentations verify
  # as "credential types not supported for issuer". No values hook exists for that key, so
  # patch the rendered ConfigMap directly. CAVEAT: a later core-platform helm upgrade
  # regenerates the ConfigMap and drops this key — re-run this script afterwards.
  kubectl get cm controlplane-config -n "$NAMESPACE" -o json \
    | jq --arg k "edc.iam.trusted-issuer.$4.supportedtypes" \
         '.data[$k] = "[\"VerifiableCredential\",\"MembershipCredential\",\"BpnCredential\",\"DataExchangeGovernanceCredential\"]"' \
    | kubectl apply -f -
  # Generous timeout: the restart re-pulls :latest images for the init containers and pays JVM
  # startup, which stacks up when several rollouts run back to back
  kubectl rollout restart deployment/controlplane -n "$NAMESPACE"
  kubectl rollout status deployment/controlplane -n "$NAMESPACE" --timeout=420s
}

add_routes "$C1" "$C2"
add_routes "$C2" "$C1"

forward_dns "$C1" "$D2" "$(dns_ip_of "$C2")"
forward_dns "$C2" "$D1" "$(dns_ip_of "$C1")"

# ---- 3. Peer gateway-hostname DNS -----------------------------------------------------------
# setup-did-dns.sh --peer forwards the peer's HTTPRoute hostnames to the peer's kube-dns and
# verifies the result, including an end-to-end fetch of the peer issuer's DID document from a
# pod — the exact resolution path the runtimes use. (This replaced the old smoke test, which
# fetched the in-cluster Service URL nothing advertises anymore.) Needs the routes and zone
# forwarding above.
"$(dirname "$0")/setup-did-dns.sh" -c "$C1" --peer "$C2"
"$(dirname "$0")/setup-did-dns.sh" -c "$C2" --peer "$C1"

trust_peer "$C1" "$D1" "$H1" "$C2" "$D2" "$H2"
trust_peer "$C2" "$D2" "$H2" "$C1" "$D1" "$H1"

echo "VEs connected: $C1 ($D1) <-> $C2 ($D2)"
