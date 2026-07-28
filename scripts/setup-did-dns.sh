#!/bin/bash

# Makes the platform's gateway hostnames resolvable from INSIDE a cluster, then verifies it.
#
# Why this is needed: the URLs core-platform-distribution advertises about itself — the DSP
# callback address, the DCP credential-service and issuance endpoints, and the did:web
# identifiers — all point at the gateway hostname
# (ve1.localhost, issuer.ve1.localhost, identity.ve1.localhost) instead of in-cluster Service
# FQDNs. Those URLs are dereferenced by the runtimes themselves: the control plane resolves the
# issuer DID to verify credential signatures and calls the CredentialService on every DSP
# message. A `*.localhost` name resolves to the pod's own loopback, so without CoreDNS help
# every one of those lookups fails.
#
# Two modes, each owning its own marker-delimited block in the Corefile (idempotent — the block
# is replaced wholesale on every run):
#
#   OWN mode (default): adds `rewrite` rules to the cluster's CoreDNS pointing each HTTPRoute
#   hostname at the cluster's own Traefik Service, so the same name resolves to the gateway from
#   inside the cluster and to 127.0.0.1 (via the KinD host port mapping) from the host.
#
#   PEER mode (--peer <cluster>): makes the PEER VE's gateway hostnames resolvable, for the
#   dual-VE setup — each side dereferences the other's advertised URLs and DIDs. A `rewrite`
#   cannot do this: it runs inside the `.:53` server block, and the rewritten name
#   (traefik.traefik.svc.<peer-domain>) does NOT re-enter server-block selection, so it bypasses
#   the `<peer-domain>:53` zone-forwarding block connect-ves.sh installs and falls through to
#   `forward . /etc/resolv.conf` -> NXDOMAIN (and `.:53` cannot take a second forward). Instead a
#   dedicated multi-zone server block forwards the peer's hostnames THEMSELVES to the peer's
#   kube-dns — whose own rewrite rules (installed by this script in OWN mode during the peer's
#   install-ve.sh) answer with the peer's Traefik ClusterIP, automatically tracking it.
#   Reachability of that ClusterIP comes from the static routes connect-ves.sh installs, so run
#   peer mode after connect-ves.sh's routing steps (connect-ves.sh does this itself).
#   Peer hostnames that the local cluster serves too (the telemetry hosts — grafana.localhost
#   etc. are identical in every VE) are excluded: a dedicated server block wins zone selection
#   over `.:53`, so forwarding them would hijack the LOCAL name.
#
# Everything except the cluster names is discovered from the clusters themselves — DNS domains,
# Traefik Services, kube-dns and the hostname lists — so the rules cannot drift from what is
# deployed. This matters most for the DNS domain: install-ve.sh gives each VE its own (ve1.local,
# ve2.local), and a rewrite target of `…svc.cluster.local` on a VE whose domain is `ve1.local`
# silently NXDOMAINs, because CoreDNS's kubernetes plugin is not authoritative for it.
#
# NOTE the Corefile changes are lost when a KinD cluster is recreated — re-run after
# install-ve.sh (own mode; install-ve.sh does it) and connect-ves.sh (peer mode; ditto).
#
# Usage:
#   ./scripts/setup-did-dns.sh [-c|--cluster <name>] [--peer <name>] [--verify-only] [-h|--help]
#
#   -c, --cluster <name>   KinD cluster whose CoreDNS is configured (default: ve1). The
#                          kubeconfig is read from ~/.kube/<name>.config, matching install-ve.sh
#       --peer <name>      peer mode: make THIS cluster resolve <name>'s gateway hostnames
#                          (peer kubeconfig from ~/.kube/<name>.config)
#       --verify-only      run the checks without touching CoreDNS
#   -h, --help             show usage and exit

set -euo pipefail

CLUSTER_NAME=ve1
PEER_NAME=""
VERIFY_ONLY=false

# Fixed, as in install-ve.sh: the CFM agents hardcode system:serviceaccount:edc-v:… client ids
NAMESPACE=edc-v

# Probe image for the verification pods. MUST be glibc-based and MUST NOT use curl: there are two
# independent RFC 6761 short-circuits that resolve `*.localhost` to loopback without ever asking
# the resolver, and either one turns a working setup into a confusing failure —
#   * musl (Alpine, so curlimages/curl too) synthesizes *.localhost in the libc;
#   * curl >= 7.77 does it internally, on ANY libc, before the resolver is consulted.
# glibc's getaddrinfo does neither, so `getent` and bash's /dev/tcp both see the real DNS — as
# does the JVM, which is what the EDC runtimes actually use. (If you do want curl here, it needs
# `--connect-to <host>:80:traefik.<ns>.svc.<domain>:80` to bypass its own short-circuit.)
PROBE_IMAGE=debian:stable-slim

usage() {
  cat <<EOF
Usage: $(basename "$0") [-c|--cluster <name>] [--peer <name>] [--verify-only] [-h|--help]

Options:
  -c, --cluster <name>  KinD cluster whose CoreDNS is configured (default: ve1). Kubeconfig is
                        read from ~/.kube/<name>.config
      --peer <name>     peer mode: make THIS cluster resolve <name>'s gateway hostnames instead
                        of its own (run after connect-ves.sh's routing/zone-forwarding steps)
      --verify-only     run the checks without modifying CoreDNS
  -h, --help            show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -c|--cluster|--peer)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -c|--cluster) CLUSTER_NAME="$2" ;;
        --peer) PEER_NAME="$2" ;;
      esac
      shift 2 ;;
    --verify-only) VERIFY_ONLY=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

kubeconfig_of() { # <cluster> -> kubeconfig path, verified readable
  local f="$HOME/.kube/$1.config"
  [[ -r "$f" ]] || {
    echo "Error: no kubeconfig at $f — was cluster '$1' created by install-ve.sh?" >&2
    exit 1
  }
  echo "$f"
}

KUBECONFIG_FILE=$(kubeconfig_of "$CLUSTER_NAME")
# Exported for the probe pods (kubectl run); discovery calls pass --kubeconfig explicitly so
# they can target either cluster.
export KUBECONFIG="$KUBECONFIG_FILE"

GEN_DIR=$(mktemp -d)
trap 'rm -rf "$GEN_DIR"' EXIT

# ---- discovery (all parameterized by kubeconfig, so peer mode can query both clusters) ------

corefile() { # <kubeconfig>
  kubectl --kubeconfig "$1" -n kube-system get cm coredns -o jsonpath='{.data.Corefile}'
}

# Cluster DNS domain, from the zone the kubernetes plugin is authoritative for. A rewrite/forward
# target must live in a resolvable zone or CoreDNS will not answer for it.
detect_dns_domain() { # <kubeconfig>
  corefile "$1" | awk '/^[[:space:]]*kubernetes[[:space:]]/{print $2; exit}'
}

# "<name> <namespace>" of the Traefik Service backing the Gateway
detect_traefik() { # <kubeconfig>
  kubectl --kubeconfig "$1" get svc -A -l app.kubernetes.io/name=traefik \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.namespace}{"\n"}{end}' | head -1
}

# Every hostname any HTTPRoute in the platform namespace is served under. Taking the full set
# (rather than a hardcoded list) keeps the rules correct as routes come and go.
detect_hostnames() { # <kubeconfig>
  kubectl --kubeconfig "$1" get httproute -n "$NAMESPACE" \
    -o jsonpath='{range .items[*]}{range .spec.hostnames[*]}{@}{"\n"}{end}{end}' | sort -u
}

# Hostname the issuer's DID document is served on (empty when the route is not exposed)
detect_issuer_did_host() { # <kubeconfig>
  kubectl --kubeconfig "$1" get httproute -n "$NAMESPACE" issuerservice-did \
    -o jsonpath='{.spec.hostnames[0]}' 2>/dev/null || true
}

read_hostnames_into() { # <array-name> <kubeconfig>  (plain loop: macOS bash 3.2 has no mapfile)
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && eval "$1+=(\"\$line\")"
  done < <(detect_hostnames "$2")
}

# ---- Corefile block management --------------------------------------------------------------
# Each mode owns one block between exact-match marker lines, replaced wholesale per run. Markers
# are compared as whole (whitespace-trimmed) lines, so the own block and per-peer blocks can
# never swallow each other, including peers whose names are prefixes of one another.

strip_block() { # <begin-marker> <end-marker>  — stdin -> stdout without the block
  awk -v b="$1" -v e="$2" '
    { t = $0; sub(/^[ \t]+/, "", t) }
    t == b { skip = 1 }
    !skip  { print }
    t == e { skip = 0 }
  '
}

apply_corefile() { # <new-corefile> <description>
  if diff -q <(corefile "$KUBECONFIG_FILE") "$1" >/dev/null 2>&1; then
    echo ">> CoreDNS already up to date ($2)"
    return
  fi
  echo ">> applying CoreDNS $2"
  kubectl -n kube-system create configmap coredns \
    --from-file=Corefile="$1" --dry-run=client -o yaml \
    | kubectl apply -f - >/dev/null
  # The Corefile has the `reload` plugin; the restart only makes the change take effect
  # immediately rather than within its poll interval.
  kubectl -n kube-system rollout restart deployment/coredns >/dev/null
  kubectl -n kube-system rollout status deployment/coredns --timeout=120s >/dev/null
  echo ">> CoreDNS reloaded"
}

# ---- verification ---------------------------------------------------------------------------

# Every hostname must resolve, from a pod in THIS cluster, to the given IP. Values reach the
# probe as environment variables so its script can stay single-quoted.
verify_dns() { # <expected-ip> <hostname...>
  local expect="$1"; shift
  echo ">> verifying in-cluster DNS resolution"
  kubectl run "dnscheck-$RANDOM" --rm -i --restart=Never --image="$PROBE_IMAGE" \
    --env="NAMES=$*" --env="EXPECT_IP=$expect" \
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

# End-to-end: fetch the issuer DID document through the gateway (from a pod in THIS cluster) and
# confirm the document's own id matches the DID. That equality is the point — the IssuerService
# reconstructs the DID from the request URL (authority + path) and looks it up by exact match, so
# a mismatch means the route, the Host header or the DID itself is wrong, even when DNS is fine.
verify_did_document() { # <did-host>
  local did_host="$1"
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

# ---- OWN mode: rewrite this cluster's hostnames to its own Traefik --------------------------

run_own_mode() {
  local begin_marker="# BEGIN cx-ve did-dns:own (managed by setup-did-dns.sh)"
  local end_marker="# END cx-ve did-dns:own"

  local dns_domain traefik_name traefik_ns traefik_fqdn traefik_ip
  dns_domain=$(detect_dns_domain "$KUBECONFIG_FILE")
  [[ -n "$dns_domain" ]] || { echo "Error: could not read the cluster DNS domain from the Corefile" >&2; exit 1; }
  read -r traefik_name traefik_ns <<< "$(detect_traefik "$KUBECONFIG_FILE")"
  [[ -n "${traefik_name:-}" ]] || { echo "Error: no Service labelled app.kubernetes.io/name=traefik found" >&2; exit 1; }
  traefik_fqdn="${traefik_name}.${traefik_ns}.svc.${dns_domain}"
  traefik_ip=$(kubectl --kubeconfig "$KUBECONFIG_FILE" -n "$traefik_ns" get svc "$traefik_name" -o jsonpath='{.spec.clusterIP}')

  local hostnames=()
  read_hostnames_into hostnames "$KUBECONFIG_FILE"
  [[ ${#hostnames[@]} -gt 0 ]] || { echo "Error: no HTTPRoutes found in namespace $NAMESPACE" >&2; exit 1; }

  echo "Cluster:      $CLUSTER_NAME (kubeconfig $KUBECONFIG_FILE)"
  echo "DNS domain:   $dns_domain"
  echo "Gateway:      $traefik_fqdn ($traefik_ip)"
  echo "Hostnames:    ${hostnames[*]}"
  echo

  if [[ "$VERIFY_ONLY" == "false" ]]; then
    # Block content in a file: BSD awk (macOS) rejects newlines in -v assignments, and sed's `r`
    # reads a file verbatim on both BSD and GNU.
    local h
    printf '    %s\n' "$begin_marker" > "$GEN_DIR/block"
    for h in "${hostnames[@]}"; do
      printf '    rewrite name %s %s\n' "$h" "$traefik_fqdn" >> "$GEN_DIR/block"
    done
    printf '    %s\n' "$end_marker" >> "$GEN_DIR/block"

    # Replace the managed block, inserted at the top of the .:53 server block. Placement is
    # cosmetic — CoreDNS plugin ordering is fixed at build time by plugin.cfg, and `rewrite`
    # runs before `kubernetes` wherever it appears.
    corefile "$KUBECONFIG_FILE" \
      | strip_block "$begin_marker" "$end_marker" \
      | sed "/^\\.:53 {/r ${GEN_DIR}/block" \
      > "$GEN_DIR/Corefile"
    apply_corefile "$GEN_DIR/Corefile" "rewrites for ${CLUSTER_NAME}'s own hostnames"
    echo
  fi

  verify_dns "$traefik_ip" "${hostnames[@]}"
  echo

  local did_host
  did_host=$(detect_issuer_did_host "$KUBECONFIG_FILE")
  if [[ -n "$did_host" ]]; then
    verify_did_document "$did_host"
  else
    echo ">> skipping DID document check: no issuerservice-did HTTPRoute"
    echo "   (expected when edc.issuerservice.did.exposed=false)"
  fi
  echo
  echo "DID DNS setup verified for cluster '$CLUSTER_NAME'."
}

# ---- PEER mode: forward the peer's hostnames to the peer's kube-dns -------------------------

run_peer_mode() {
  local peer_kubeconfig
  peer_kubeconfig=$(kubeconfig_of "$PEER_NAME")

  local begin_marker="# BEGIN cx-ve did-dns:peer:${PEER_NAME} (managed by setup-did-dns.sh)"
  local end_marker="# END cx-ve did-dns:peer:${PEER_NAME}"

  # Peer-side facts: its hostnames, its kube-dns (the forward target) and its Traefik ClusterIP
  # (what those names must resolve to once forwarded).
  local peer_dns_ip peer_traefik_name peer_traefik_ns peer_traefik_ip
  peer_dns_ip=$(kubectl --kubeconfig "$peer_kubeconfig" -n kube-system get svc kube-dns -o jsonpath='{.spec.clusterIP}')
  [[ -n "$peer_dns_ip" ]] || { echo "Error: could not read kube-dns ClusterIP of peer '$PEER_NAME'" >&2; exit 1; }
  read -r peer_traefik_name peer_traefik_ns <<< "$(detect_traefik "$peer_kubeconfig")"
  [[ -n "${peer_traefik_name:-}" ]] || { echo "Error: no Traefik Service found in peer '$PEER_NAME'" >&2; exit 1; }
  peer_traefik_ip=$(kubectl --kubeconfig "$peer_kubeconfig" -n "$peer_traefik_ns" get svc "$peer_traefik_name" -o jsonpath='{.spec.clusterIP}')

  local own_hostnames=() peer_hostnames=()
  read_hostnames_into own_hostnames "$KUBECONFIG_FILE"
  read_hostnames_into peer_hostnames "$peer_kubeconfig"
  # Both must be non-empty — also keeps bash 3.2 (macOS) happy, where expanding an empty array
  # under `set -u` is an unbound-variable error.
  [[ ${#own_hostnames[@]} -gt 0 ]] || { echo "Error: no HTTPRoutes found in namespace $NAMESPACE of '$CLUSTER_NAME' — is the platform installed?" >&2; exit 1; }
  [[ ${#peer_hostnames[@]} -gt 0 ]] || { echo "Error: no HTTPRoutes found in peer '$PEER_NAME'" >&2; exit 1; }

  # Collision filter: never forward a name this cluster serves itself (telemetry hosts are
  # identical in every VE) — a dedicated server block wins zone selection over `.:53` and would
  # hijack the local name.
  local forwarded=() h o skip
  for h in "${peer_hostnames[@]}"; do
    skip=false
    for o in "${own_hostnames[@]}"; do
      [[ "$h" == "$o" ]] && { skip=true; break; }
    done
    [[ "$skip" == "false" ]] && forwarded+=("$h")
  done
  [[ ${#forwarded[@]} -gt 0 ]] || { echo "Error: every peer hostname collides with a local one — nothing to forward" >&2; exit 1; }

  echo "Cluster:      $CLUSTER_NAME (kubeconfig $KUBECONFIG_FILE)"
  echo "Peer:         $PEER_NAME (kube-dns $peer_dns_ip, gateway $peer_traefik_ip)"
  echo "Forwarded:    ${forwarded[*]}"
  echo "Skipped:      $(comm -12 <(printf '%s\n' "${peer_hostnames[@]}") <(printf '%s\n' "${own_hostnames[@]}") | tr '\n' ' ')(served locally)"
  echo

  if [[ "$VERIFY_ONLY" == "false" ]]; then
    # One multi-zone server block, appended at file end (server blocks are top-level; zone
    # selection picks the longest matching zone regardless of order).
    local zones=""
    for h in "${forwarded[@]}"; do zones+="${h}:53 "; done
    {
      printf '%s\n' "$begin_marker"
      printf '%s{\n' "$zones"
      printf '    errors\n    cache 30\n    forward . %s\n' "$peer_dns_ip"
      printf '}\n'
      printf '%s\n' "$end_marker"
    } > "$GEN_DIR/block"

    corefile "$KUBECONFIG_FILE" | strip_block "$begin_marker" "$end_marker" > "$GEN_DIR/Corefile"
    cat "$GEN_DIR/block" >> "$GEN_DIR/Corefile"
    apply_corefile "$GEN_DIR/Corefile" "forwarding for peer '${PEER_NAME}' hostnames"
    echo
  fi

  # Both checks need connect-ves.sh's static routes: the DNS query goes to the peer's kube-dns
  # ClusterIP, the document fetch to the peer's Traefik ClusterIP.
  verify_dns "$peer_traefik_ip" "${forwarded[@]}"
  echo

  local peer_did_host
  peer_did_host=$(detect_issuer_did_host "$peer_kubeconfig")
  if [[ -n "$peer_did_host" ]]; then
    verify_did_document "$peer_did_host"
  else
    echo ">> skipping DID document check: peer has no issuerservice-did HTTPRoute"
  fi
  echo
  echo "Peer DID DNS verified: '$CLUSTER_NAME' resolves '$PEER_NAME'."
}

# ---- main -----------------------------------------------------------------------------------

if [[ -n "$PEER_NAME" ]]; then
  run_peer_mode
else
  run_own_mode
fi
