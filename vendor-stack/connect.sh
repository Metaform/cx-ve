#!/bin/bash

# Makes the Verification Environment (VE) and the vendor stack reachable from each other's pods,
# then proves it in both directions.
#
# Every exchange between the two is dereferenced by a runtime INSIDE a cluster: the VE's issuer
# resolves the vendor participant's did:web and delivers credentials to its CredentialService, the
# vendor's IdentityHub requests them from the VE's issuance API, both control planes resolve each
# other's DIDs and dial each other's DSP endpoints, and the two Certo instances call each other
# over the flows' endpoints. All of those URLs live under three gateway hostnames per side —
# <host>, issuer.<host>, identity.<host> — and a *.localhost name resolves to the pod itself.
#
# Both kind clusters sit on the docker `kind` network, and each gateway is published on its node
# container (Traefik hostPort: 80 for the VE, 8080 for the vendor). So no routing is needed: each
# side's CoreDNS gets a `hosts` entry pointing the OTHER side's hostnames at the other node's IP,
# and the port in the URL does the rest. The entries are written by scripts/setup-did-dns.sh
# --sut, the same tool that manages each cluster's own rewrites.
#
# Idempotent — the managed block is replaced wholesale. Re-run after re-installing either cluster
# or restarting docker (node IPs are reassigned).
#
# Usage:
#   ./vendor-stack/connect.sh [--ve-cluster <name>] [--ve-host <host>] [--ve-port <port>]
#                             [-c|--cluster <name>] [-H|--host <host>] [-p|--port <port>] [-h|--help]
#
#   --ve-cluster <name>   the VE's kind cluster (default: cxve)
#   --ve-host <host>      the VE's gateway hostname (default: cxve.localhost)
#   --ve-port <port>      the VE's gateway port (default: 80)
#   -c, --cluster <name>  the vendor stack's kind cluster (default: vendor)
#   -H, --host <host>     the vendor stack's gateway hostname (default: vendor.localhost)
#   -p, --port <port>     the vendor stack's gateway port (default: 8080)
#
# Requires: docker, kubectl (kubeconfigs at ~/.kube/<cluster>.config)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DNS_TOOL="$SCRIPT_DIR/../scripts/setup-did-dns.sh"

VE_CLUSTER=cxve
VE_HOST=cxve.localhost
VE_PORT=80
VENDOR_CLUSTER=vendor
VENDOR_HOST=vendor.localhost
VENDOR_PORT=8080
# glibc and no curl — see the PROBE_IMAGE note in scripts/setup-did-dns.sh
PROBE_IMAGE=debian:stable-slim

usage() {
  cat <<EOF
Usage: $(basename "$0") [--ve-cluster <name>] [--ve-host <host>] [--ve-port <port>]
                  [-c|--cluster <name>] [-H|--host <host>] [-p|--port <port>] [-h|--help]

Options:
  --ve-cluster <name>   the VE's kind cluster (default: cxve)
  --ve-host <host>      the VE's gateway hostname (default: cxve.localhost)
  --ve-port <port>      the VE's gateway port (default: 80)
  -c, --cluster <name>  the vendor stack's kind cluster (default: vendor)
  -H, --host <host>     the vendor stack's gateway hostname (default: vendor.localhost)
  -p, --port <port>     the vendor stack's gateway port (default: 8080)
  -h, --help            show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ve-cluster|--ve-host|--ve-port|-c|--cluster|-H|--host|-p|--port)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        --ve-cluster) VE_CLUSTER="$2" ;;
        --ve-host) VE_HOST="$2" ;;
        --ve-port) VE_PORT="$2" ;;
        -c|--cluster) VENDOR_CLUSTER="$2" ;;
        -H|--host) VENDOR_HOST="$2" ;;
        -p|--port) VENDOR_PORT="$2" ;;
      esac
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
  esac
done

node_ip() { # <cluster> -> the control-plane node's address on the docker kind network
  local ip
  ip=$(docker inspect -f '{{with index .NetworkSettings.Networks "kind"}}{{.IPAddress}}{{end}}' \
    "$1-control-plane" 2>/dev/null || true)
  [[ -n "$ip" ]] || { echo "Error: no kind node '$1-control-plane' on the docker 'kind' network" >&2; exit 1; }
  echo "$ip"
}

# Fetches http://issuer.<host>[:<port>]/issuer/did.json from a pod in <cluster> and checks the
# document is the one the DID names. Proves DNS, cross-node reachability of the gateway port, the
# route and the Host-header-derived DID in one go.
probe_issuer() { # <cluster> <host> <port>
  local cluster="$1" host="issuer.$2" port="$3" authority did_authority
  authority="$host"; did_authority="$host"
  if [[ "$port" != 80 ]]; then
    authority="$host:$port"; did_authority="$host%3A$port"
  fi
  echo ">> from cluster '$cluster': GET http://$authority/issuer/did.json"
  kubectl --kubeconfig "$HOME/.kube/$cluster.config" -n default run "peercheck-$RANDOM" --rm -i \
    --restart=Never --image="$PROBE_IMAGE" \
    --env="HOST=$host" --env="PORT=$port" --env="AUTHORITY=$authority" \
    --env="EXPECT=\"id\":\"did:web:$did_authority:issuer\"" \
    --timeout=300s --command -- bash -c '
      for attempt in $(seq 1 24); do
        response=""
        if { exec 3<>"/dev/tcp/$HOST/$PORT"; } 2>/dev/null; then
          printf "GET /issuer/did.json HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" "$AUTHORITY" >&3
          response=$(cat <&3)
          exec 3<&-
        fi
        printf "%s" "$response" | grep -qF "$EXPECT" && break
        [ "$attempt" -lt 24 ] && { echo "     ...  not reachable yet, retrying ($attempt/24)"; sleep 5; }
      done
      if printf "%s" "$response" | grep -qF "$EXPECT"; then
        echo "     OK   $(printf "%s" "$response" | head -1 | tr -d "\r"), document id matches"
      else
        echo "     FAIL expected $EXPECT"
        [ -n "$response" ] && printf "%s" "$response" | head -1 | sed "s/^/          /"
        exit 1
      fi'
}

VE_IP=$(node_ip "$VE_CLUSTER")
VENDOR_IP=$(node_ip "$VENDOR_CLUSTER")
echo "VE:      $VE_CLUSTER ($VE_HOST:$VE_PORT) at $VE_IP"
echo "Vendor:  $VENDOR_CLUSTER ($VENDOR_HOST:$VENDOR_PORT) at $VENDOR_IP"
echo

echo "==== VE cluster: resolve the vendor stack ===================================================="
"$DNS_TOOL" -c "$VE_CLUSTER" -p "$VE_PORT" \
  --sut "$VENDOR_HOST=$VENDOR_IP" \
  --sut "identity.$VENDOR_HOST=$VENDOR_IP" \
  --sut "issuer.$VENDOR_HOST=$VENDOR_IP"
echo

echo "==== vendor cluster: resolve the VE =========================================================="
"$DNS_TOOL" -c "$VENDOR_CLUSTER" -p "$VENDOR_PORT" \
  --sut "$VE_HOST=$VE_IP" \
  --sut "identity.$VE_HOST=$VE_IP" \
  --sut "issuer.$VE_HOST=$VE_IP"
echo

echo "==== reachability across the clusters ========================================================"
probe_issuer "$VE_CLUSTER" "$VENDOR_HOST" "$VENDOR_PORT"
probe_issuer "$VENDOR_CLUSTER" "$VE_HOST" "$VE_PORT"
echo
echo "Connected: $VE_CLUSTER <-> $VENDOR_CLUSTER"
