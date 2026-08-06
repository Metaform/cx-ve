#!/bin/bash

# Makes the VE's gateway hostnames resolvable from INSIDE the cluster, then verifies it.
#
# Why this is needed: the URLs core-platform-distribution advertises about itself — the DSP
# callback address, the DCP credential-service and issuance endpoints, and the did:web
# identifiers — all point at the gateway hostname
# (cxve.localhost, issuer.cxve.localhost, identity.cxve.localhost) instead of in-cluster
# Service FQDNs. Those URLs are dereferenced by the runtimes themselves: the control plane
# resolves the issuer DID to verify credential signatures and calls the CredentialService on
# every DSP message. A `*.localhost` name resolves to the pod's own loopback, so without
# CoreDNS help every one of those lookups fails.
#
# It adds `rewrite` rules to the cluster's CoreDNS pointing each HTTPRoute hostname at the
# cluster's own Traefik Service, so the same name resolves to the gateway from inside the
# cluster and to 127.0.0.1 (via the KinD host port mapping) from the host. The rules live in a
# marker-delimited block in the Corefile, replaced wholesale on every run (idempotent).
#
# Everything except the cluster name is discovered from the cluster itself — DNS domain,
# Traefik Service and the hostname list — so the rules cannot drift from what is deployed.
# The DNS domain matters most: a rewrite target of `…svc.cluster.local` on a cluster with a
# custom kubeadm dnsDomain silently NXDOMAINs, because CoreDNS's kubernetes plugin is not
# authoritative for it.
#
# NOTE the Corefile changes are lost when the KinD cluster is recreated — re-run after
# install-ve.sh (which does it itself).
#
# Two-phase use with the cx-ve umbrella release: the profile/certo seed hooks of the single
# release already need in-cluster DNS while the install is still running, so there is no
# "between the releases" moment to patch CoreDNS in. Instead:
#   1. BEFORE the umbrella install:  setup-did-dns.sh --pre -H <host>
#      writes the rewrite block from the DERIVED hostname list (<host>, issuer.<host>,
#      identity.<host>) — no HTTPRoutes exist yet — and verifies plain DNS resolution.
#   2. AFTER the install: a normal run re-derives the list from the deployed HTTPRoutes
#      (replacing the marker block wholesale, so the rules cannot drift from what is actually
#      routed) and additionally verifies the issuer DID document.
#
# Usage:
#   ./scripts/setup-did-dns.sh [-c|--cluster <name>] [--pre] [-H|--host <host>]
#                              [--verify-only] [-h|--help]
#
#   -c, --cluster <name>   KinD cluster whose CoreDNS is configured (default: cxve). The
#                          kubeconfig is read from ~/.kube/<name>.config, matching install-ve.sh
#       --pre              pre-install mode: derive the hostnames from --host instead of the
#                          (not yet existing) HTTPRoutes, and skip the DID document check
#   -H, --host <host>      the VE's hostname the derived list is built from (default:
#                          cxve.localhost; only used with --pre)
#       --verify-only      run the checks without touching CoreDNS
#   -h, --help             show usage and exit

set -euo pipefail

CLUSTER_NAME=cxve
VERIFY_ONLY=false
PRE=false
HOST=cxve.localhost

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
Usage: $(basename "$0") [-c|--cluster <name>] [--pre] [-H|--host <host>] [--verify-only] [-h|--help]

Options:
  -c, --cluster <name>  KinD cluster whose CoreDNS is configured (default: cxve). Kubeconfig is
                        read from ~/.kube/<name>.config
      --pre             pre-install mode: derive hostnames from --host instead of the (not yet
                        existing) HTTPRoutes; skips the DID document check
  -H, --host <host>     the VE hostname the derived list is built from (default: cxve.localhost;
                        only used with --pre)
      --verify-only     run the checks without modifying CoreDNS
  -h, --help            show this help
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
      shift 2 ;;
    --pre) PRE=true; shift ;;
    --verify-only) VERIFY_ONLY=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ "$PRE" == "true" && "$VERIFY_ONLY" == "true" ]]; then
  echo "Error: --pre and --verify-only are mutually exclusive (--pre exists to patch before install)" >&2
  exit 1
fi

kubeconfig_of() { # <cluster> -> kubeconfig path, verified readable
  local f="$HOME/.kube/$1.config"
  [[ -r "$f" ]] || {
    echo "Error: no kubeconfig at $f — was cluster '$1' created by install-ve.sh?" >&2
    exit 1
  }
  echo "$f"
}

KUBECONFIG_FILE=$(kubeconfig_of "$CLUSTER_NAME")
# Exported for the probe pods (kubectl run); discovery calls pass --kubeconfig explicitly.
export KUBECONFIG="$KUBECONFIG_FILE"

GEN_DIR=$(mktemp -d)
trap 'rm -rf "$GEN_DIR"' EXIT

# ---- discovery -------------------------------------------------------------------------------

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
# The script owns one block between exact-match marker lines, replaced wholesale per run.
# Markers are compared as whole (whitespace-trimmed) lines so unrelated Corefile content can
# never be swallowed.

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
#
# The probe retries before judging: apply_corefile's rollout wait does not make the new config
# authoritative — terminating pods keep answering with the OLD Corefile through their lameduck
# window, and the kubelet can hand a freshly started pod a stale ConfigMap volume (healed by its
# periodic sync plus CoreDNS's `reload` poll, worst case ~2 minutes). Inside that window a
# *.localhost query falls through `.:53` to the host resolver, which per RFC 6761 answers ::1 —
# a transient WRONG answer, not a timeout, so only comparing against the expected IP catches it.
verify_dns() { # <expected-ip> <hostname...>
  local expect="$1"; shift
  echo ">> verifying in-cluster DNS resolution"
  kubectl run "dnscheck-$RANDOM" --rm -i --restart=Never --image="$PROBE_IMAGE" \
    --env="NAMES=$*" --env="EXPECT_IP=$expect" \
    --timeout=300s --command -- sh -c '
      attempt=1
      while :; do
        fail=0
        for n in $NAMES; do
          ip=$(getent ahosts "$n" 2>/dev/null | awk "{print \$1; exit}")
          [ "$ip" = "$EXPECT_IP" ] || fail=1
        done
        [ $fail -eq 0 ] || [ $attempt -ge 36 ] && break
        echo "     ...  not propagated yet, retrying ($attempt/36)"
        attempt=$((attempt + 1))
        sleep 5
      done
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
  # Retries for the same reason as verify_dns — and a dnscheck pass does not cover this probe:
  # CoreDNS runs two replicas, so this lookup may hit a replica the dnscheck never exercised.
  kubectl run "didcheck-$RANDOM" --rm -i --restart=Never --image="$PROBE_IMAGE" \
    --env="DID_HOST=$did_host" --env="DID_PATH=$path" --env="EXPECT=$expect" \
    --timeout=300s --command -- bash -c '
      for attempt in $(seq 1 36); do
        response=""
        if { exec 3<>"/dev/tcp/$DID_HOST/80"; } 2>/dev/null; then
          printf "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" "$DID_PATH" "$DID_HOST" >&3
          response=$(cat <&3)
          exec 3<&-
        fi
        printf "%s" "$response" | grep -qF "$EXPECT" && break
        [ "$attempt" -lt 36 ] && { echo "     ...  document not served yet, retrying ($attempt/36)"; sleep 5; }
      done
      if [ -z "$response" ]; then
        echo "     FAIL could not connect to $DID_HOST:80"; exit 1
      fi
      printf "     %s\n" "$(printf "%s" "$response" | head -1 | tr -d "\r")"
      if printf "%s" "$response" | grep -qF "$EXPECT"; then
        echo "     OK   document id matches the DID"
      else
        echo "     FAIL expected $EXPECT in the response body"
        printf "%s" "$response" | tail -3 | sed "s/^/          /"
        exit 1
      fi'
}

# ---- rewrite the cluster's hostnames to its own Traefik -------------------------------------

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
  if [[ "$PRE" == "true" ]]; then
    # Pre-install: no HTTPRoutes to discover from yet — derive the platform's gateway hostnames
    # from the VE host. The post-install run replaces this block wholesale from the actual
    # routes, so any drift is corrected then.
    hostnames=("$HOST" "issuer.$HOST" "identity.$HOST")
  else
    read_hostnames_into hostnames "$KUBECONFIG_FILE"
    [[ ${#hostnames[@]} -gt 0 ]] || { echo "Error: no HTTPRoutes found in namespace $NAMESPACE" >&2; exit 1; }
  fi

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

  if [[ "$PRE" == "true" ]]; then
    echo ">> skipping DID document check (pre-install mode: nothing serves it yet)"
    echo
    echo "Pre-install DID DNS setup done for cluster '$CLUSTER_NAME' — re-run without --pre after the install."
    return
  fi

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

# ---- main -----------------------------------------------------------------------------------

run_own_mode
