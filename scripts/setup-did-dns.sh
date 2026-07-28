#!/bin/bash

# Makes the platform's gateway hostnames resolvable from INSIDE the cluster, then verifies it.
#
# Why this is needed: the URLs core-platform-distribution advertises about itself — the DSP
# callback address, the DCP credential-service and issuance endpoints, and the did:web
# identifiers — all point at the gateway hostname
# (ve1.localhost, issuer.ve1.localhost, identity.ve1.localhost) instead of in-cluster Service
# FQDNs. Those URLs are dereferenced by the runtimes themselves: the control plane resolves the
# issuer DID to verify credential signatures and calls the CredentialService on every DSP
# message. A `*.localhost` name resolves to the pod's own loopback, so without a CoreDNS rewrite
# every one of those lookups fails.
#
# This script adds `rewrite` rules to the cluster's CoreDNS Corefile pointing each HTTPRoute
# hostname at the Traefik Service, so the same name resolves to the gateway from inside the
# cluster and to 127.0.0.1 (via the KinD host port mapping) from the host. It is idempotent: the
# rules live between marker comments and are replaced wholesale on every run.
#
# Everything except the cluster name is discovered from the cluster itself — the DNS domain, the
# Traefik Service and the hostname list — so the rules cannot drift from what is deployed. This
# matters most for the DNS domain: install-ve.sh gives each VE its own (ve1.local, ve2.local),
# and a rewrite target of `…svc.cluster.local` on a VE whose domain is `ve1.local` silently
# NXDOMAINs, because CoreDNS's kubernetes plugin is not authoritative for it.
#
# NOTE the rewrite is lost when the KinD cluster is recreated — re-run this after install-ve.sh.
#
# NOTE for the dual-VE setup: each cluster additionally needs the PEER's hostnames rewritten to
# `traefik.traefik.svc.<peer-dns-domain>`, which resolves over the zone forwarding and static
# routes that connect-ves.sh installs. That is not handled here; this script only wires up the
# cluster it is pointed at.
#
# Usage:
#   ./scripts/setup-did-dns.sh [-c|--cluster <name>] [--verify-only] [-h|--help]
#
#   -c, --cluster <name>   KinD cluster to configure (default: ve1). The kubeconfig is read from
#                          ~/.kube/<name>.config, matching install-ve.sh
#       --verify-only      run the checks without touching CoreDNS
#   -h, --help             show usage and exit

set -euo pipefail

CLUSTER_NAME=ve1
VERIFY_ONLY=false

# Fixed, as in install-ve.sh: the CFM agents hardcode system:serviceaccount:edc-v:… client ids
NAMESPACE=edc-v

# Marker comments delimiting the block this script owns inside the Corefile
BEGIN_MARKER="# BEGIN cx-ve did-dns (managed by setup-did-dns.sh)"
END_MARKER="# END cx-ve did-dns"

# Probe image for the verification pods. MUST be glibc-based and MUST NOT use curl: there are two
# independent RFC 6761 short-circuits that resolve `*.localhost` to loopback without ever asking
# the resolver, and either one turns a working rewrite into a confusing failure —
#   * musl (Alpine, so curlimages/curl too) synthesizes *.localhost in the libc;
#   * curl >= 7.77 does it internally, on ANY libc, before the resolver is consulted.
# glibc's getaddrinfo does neither, so `getent` and bash's /dev/tcp both see the rewrite — as does
# the JVM, which is what the EDC runtimes actually use. (If you do want curl here, it needs
# `--connect-to <host>:80:traefik.<ns>.svc.<domain>:80` to bypass its own short-circuit.)
PROBE_IMAGE=debian:stable-slim

usage() {
  cat <<EOF
Usage: $(basename "$0") [-c|--cluster <name>] [--verify-only] [-h|--help]

Options:
  -c, --cluster <name>  KinD cluster to configure (default: ve1). Kubeconfig is read from
                        ~/.kube/<name>.config
      --verify-only     run the checks without modifying CoreDNS
  -h, --help            show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      CLUSTER_NAME="$2"; shift 2 ;;
    --verify-only) VERIFY_ONLY=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

KUBECONFIG_FILE="$HOME/.kube/$CLUSTER_NAME.config"
[[ -r "$KUBECONFIG_FILE" ]] || {
  echo "Error: no kubeconfig at $KUBECONFIG_FILE — was the cluster created by install-ve.sh?" >&2
  exit 1
}
export KUBECONFIG="$KUBECONFIG_FILE"

GEN_DIR=$(mktemp -d)
trap 'rm -rf "$GEN_DIR"' EXIT

# ---- discovery ------------------------------------------------------------------------------

corefile() {
  kubectl -n kube-system get cm coredns -o jsonpath='{.data.Corefile}'
}

# Cluster DNS domain, from the zone the kubernetes plugin is authoritative for. The rewrite
# target must live in this domain or CoreDNS will not answer for it.
detect_dns_domain() {
  corefile | awk '/^[[:space:]]*kubernetes[[:space:]]/{print $2; exit}'
}

# "<name> <namespace>" of the Traefik Service backing the Gateway
detect_traefik() {
  kubectl get svc -A -l app.kubernetes.io/name=traefik \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.namespace}{"\n"}{end}' | head -1
}

# Every hostname any HTTPRoute in the platform namespace is served under. Taking the full set
# (rather than a hardcoded list) keeps the rewrites correct as routes come and go, and covers the
# telemetry hosts too — harmless, since in-cluster nothing wants loopback for those names either.
detect_hostnames() {
  kubectl get httproute -n "$NAMESPACE" \
    -o jsonpath='{range .items[*]}{range .spec.hostnames[*]}{@}{"\n"}{end}{end}' | sort -u
}

DNS_DOMAIN=$(detect_dns_domain)
[[ -n "$DNS_DOMAIN" ]] || { echo "Error: could not read the cluster DNS domain from the Corefile" >&2; exit 1; }

read -r TRAEFIK_NAME TRAEFIK_NS <<< "$(detect_traefik)"
[[ -n "${TRAEFIK_NAME:-}" ]] || { echo "Error: no Service labelled app.kubernetes.io/name=traefik found" >&2; exit 1; }
TRAEFIK_FQDN="${TRAEFIK_NAME}.${TRAEFIK_NS}.svc.${DNS_DOMAIN}"

# Plain read loop rather than mapfile: /bin/bash on macOS is 3.2, which predates it.
HOSTNAMES=()
while IFS= read -r line; do
  [[ -n "$line" ]] && HOSTNAMES+=("$line")
done < <(detect_hostnames)
[[ ${#HOSTNAMES[@]} -gt 0 ]] || { echo "Error: no HTTPRoutes found in namespace $NAMESPACE" >&2; exit 1; }

TRAEFIK_IP=$(kubectl -n "$TRAEFIK_NS" get svc "$TRAEFIK_NAME" -o jsonpath='{.spec.clusterIP}')

echo "Cluster:      $CLUSTER_NAME (kubeconfig $KUBECONFIG_FILE)"
echo "DNS domain:   $DNS_DOMAIN"
echo "Gateway:      $TRAEFIK_FQDN ($TRAEFIK_IP)"
echo "Hostnames:    ${HOSTNAMES[*]}"
echo

# ---- 1. CoreDNS rewrites --------------------------------------------------------------------

apply_rewrites() {
  # The block goes in a file rather than an awk -v variable: BSD awk (macOS) rejects literal
  # newlines in -v assignments, and sed's `r` reads it verbatim on both BSD and GNU.
  local h
  printf '    %s\n' "$BEGIN_MARKER" > "$GEN_DIR/block"
  for h in "${HOSTNAMES[@]}"; do
    printf '    rewrite name %s %s\n' "$h" "$TRAEFIK_FQDN" >> "$GEN_DIR/block"
  done
  printf '    %s\n' "$END_MARKER" >> "$GEN_DIR/block"

  # Replace any previously managed block, then insert the new one at the top of the .:53 server
  # block. Placement is cosmetic — CoreDNS plugin ordering is fixed at build time by plugin.cfg,
  # and `rewrite` runs before `kubernetes` wherever it appears.
  corefile \
    | awk '/# BEGIN cx-ve did-dns/{skip=1} !skip{print} /# END cx-ve did-dns/{skip=0}' \
    | sed "/^\\.:53 {/r ${GEN_DIR}/block" \
    > "$GEN_DIR/Corefile"

  if diff -q <(corefile) "$GEN_DIR/Corefile" >/dev/null 2>&1; then
    echo ">> CoreDNS rewrites already up to date"
    return
  fi

  echo ">> applying CoreDNS rewrites:"
  grep "rewrite name" "$GEN_DIR/Corefile" | sed 's/^/     /'
  kubectl -n kube-system create configmap coredns \
    --from-file=Corefile="$GEN_DIR/Corefile" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  # The Corefile has the `reload` plugin, so this only makes the change take effect immediately
  # rather than within its poll interval.
  kubectl -n kube-system rollout restart deployment/coredns >/dev/null
  kubectl -n kube-system rollout status deployment/coredns --timeout=120s >/dev/null
  echo ">> CoreDNS reloaded"
}

# ---- 2. Verification ------------------------------------------------------------------------

# Every hostname must resolve, from a pod, to the Traefik ClusterIP. Values reach the probe as
# environment variables so its script can stay single-quoted — nothing here is expanded by the
# outer shell, which keeps the quoting readable.
verify_dns() {
  echo ">> verifying in-cluster DNS resolution"
  kubectl run "dnscheck-$RANDOM" --rm -i --restart=Never --image="$PROBE_IMAGE" \
    --env="NAMES=${HOSTNAMES[*]}" --env="EXPECT_IP=$TRAEFIK_IP" \
    --timeout=180s --command -- sh -c '
      fail=0
      for n in $NAMES; do
        ip=$(getent ahosts "$n" 2>/dev/null | awk "{print \$1; exit}")
        if [ "$ip" = "$EXPECT_IP" ]; then
          printf "     OK   %-32s -> %s\n" "$n" "$ip"
        else
          printf "     FAIL %-32s -> %s [expected %s]\n" "$n" "${ip:-NXDOMAIN}" "$EXPECT_IP"
          fail=1
        fi
      done
      exit $fail'
}

# End-to-end: fetch the issuer DID document through the gateway and confirm the document's own id
# matches the DID. That equality is the point — IdentityHub/IssuerService reconstruct the DID from
# the request URL (authority + path) and look it up by exact match, so a mismatch means the route,
# the Host header or the DID itself is wrong, even when DNS is fine.
verify_did_document() {
  local did_host
  did_host=$(kubectl get httproute -n "$NAMESPACE" issuerservice-did \
    -o jsonpath='{.spec.hostnames[0]}' 2>/dev/null || true)
  if [[ -z "$did_host" ]]; then
    echo ">> skipping DID document check: no issuerservice-did HTTPRoute"
    echo "   (expected when edc.issuerservice.did.exposed=false)"
    return 0
  fi

  # "issuer" is the issuer's participantContextId, created by the platform's issuerservice-seed
  # job and hardcoded across cx-ve (see issuer.id in the reg/onboarding agent configs).
  local path="/issuer/did.json"
  local expect="\"id\":\"did:web:${did_host}:issuer\""

  echo ">> verifying DID resolution: did:web:${did_host}:issuer"
  # bash rather than sh: the probe image ships no HTTP client, and bash's /dev/tcp needs no extra
  # image. The explicit Host header is the whole point — it is what the DID is reconstructed from.
  kubectl run "didcheck-$RANDOM" --rm -i --restart=Never --image="$PROBE_IMAGE" \
    --env="DID_HOST=$did_host" --env="DID_PATH=$path" --env="EXPECT=$expect" \
    --timeout=180s --command -- bash -c '
      exec 3<>/dev/tcp/$DID_HOST/80 2>/dev/null || {
        echo "     FAIL could not connect to $DID_HOST:80"; exit 1; }
      printf "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" "$DID_PATH" "$DID_HOST" >&3
      response=$(cat <&3)
      printf "     %s\n" "$(printf "%s" "$response" | head -1 | tr -d "\r")"
      if printf "%s" "$response" | grep -qF "$EXPECT"; then
        echo "     OK   document id matches the DID"
      else
        echo "     FAIL expected $EXPECT in the response body"
        printf "%s" "$response" | tail -3 | sed "s/^/          /"
        exit 1
      fi'
}

# ---- main -----------------------------------------------------------------------------------

if [[ "$VERIFY_ONLY" == "false" ]]; then
  apply_rewrites
  echo
fi

verify_dns
echo
verify_did_document
echo
echo "DID DNS setup verified for cluster '$CLUSTER_NAME'."
