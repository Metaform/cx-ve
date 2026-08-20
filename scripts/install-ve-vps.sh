#!/bin/bash

# Installs the complete Verification Environment (VE) on an EXISTING cluster on a VPS: ONE
# umbrella helm release (charts/cx-ve) containing the Core Platform Distribution, the Catena-X
# profile seeding, the Onboarding API, Certo and the Certo CFM agent. Run from the repository
# root.
#
# VPS variant of install-ve.sh: no kind cluster is created (the target cluster is reached
# through the kubeconfig passed in), and NO DNS magic is applied — the hostname is expected to
# be a REAL, publicly resolvable DNS name (ideally a wildcard record: <host>, issuer.<host> and
# identity.<host> must all resolve) pointing at the VPS. Because pods resolve those names
# through public DNS to the VPS itself, the CoreDNS rewrites of setup-did-dns.sh are not
# needed. The HOSTNAME (-H) is the VE's identity domain — participant and issuer DIDs embed the
# gateway hostnames derived from it (did:web:identity.<host>:<participant>,
# did:web:issuer.<host>:issuer).
#
# Unlike install-ve.sh, nothing is built from source: every image — including the Onboarding
# API, the Compliance Tracker and the Membership Hub — is pulled from its registry (the
# published images from .github/workflows/publish.yml).
#
# Traefik is installed from traefik-values.yaml, which already carries the VPS-relevant
# settings: hostPort 80/443 binding with the unprivileged-port sysctl, and the metallb
# loadBalancerIPs annotation — adjust that file if your VPS setup differs.
#
# NOTE the release name cx-ve is load-bearing: the platform chart names its infra resources
# <release>-nats / <release>-vault / <release>-postgresql, and the umbrella values reference
# those names. NOTE also the namespace is fixed to edc-v: the CFM agents hardcode
# "system:serviceaccount:edc-v:…" client ids when registering jwtlet mappings during
# participant provisioning, so any other namespace breaks provisioning with 403s.
#
# Usage:
#   ./scripts/install-ve-vps.sh -H <hostname> -k <kubeconfig> [-h|--help]
#
#   -H, --host <hostname>        REQUIRED. Publicly resolvable hostname the VE's APIs are
#                                exposed under, and the VE's identity domain — DIDs embed
#                                identity.<host> / issuer.<host>. Must point at the VPS
#                                (wildcard DNS record recommended)
#   -k, --kubeconfig <file>      REQUIRED. Kubeconfig of the target cluster on the VPS
#   -h, --help                   show usage and exit

set -euo pipefail

# Fixed: the CFM agents hardcode system:serviceaccount:edc-v:… client ids (see header)
NAMESPACE=edc-v
# Fixed: the platform derives its infra resource names from the release name (see header)
RELEASE=cx-ve
UMBRELLA_CHART=charts/cx-ve
HOST=""
KUBECONFIG_FILE=""

usage() {
  cat <<EOF
Usage: $(basename "$0") -H <hostname> -k <kubeconfig> [-h|--help]

Options:
  -H, --host <hostname>      REQUIRED. Publicly resolvable hostname of the VE's APIs and its
                             identity domain — DIDs embed identity.<host> / issuer.<host>.
                             Must point at the VPS (wildcard DNS record recommended)
  -k, --kubeconfig <file>    REQUIRED. Kubeconfig of the target cluster on the VPS
  -h, --help                 show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -H|--host|-k|--kubeconfig)
      [[ $# -ge 2 ]] || { echo "Error: $1 requires a value" >&2; usage >&2; exit 1; }
      case "$1" in
        -H|--host) HOST="$2" ;;
        -k|--kubeconfig) KUBECONFIG_FILE="$2" ;;
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

[[ -n "$HOST" ]] || { echo "Error: -H|--host is required" >&2; usage >&2; exit 1; }
[[ -n "$KUBECONFIG_FILE" ]] || { echo "Error: -k|--kubeconfig is required" >&2; usage >&2; exit 1; }
[[ -r "$KUBECONFIG_FILE" ]] || { echo "Error: kubeconfig '$KUBECONFIG_FILE' does not exist or is not readable" >&2; exit 1; }

export KUBECONFIG="$KUBECONFIG_FILE"

# Fail early if the kubeconfig doesn't reach a cluster, rather than mid-install
kubectl cluster-info >/dev/null || { echo "Error: cannot reach the cluster with kubeconfig '$KUBECONFIG_FILE'" >&2; exit 1; }

# Everything host-derived in the checked-in values, always overridden from $HOST so the chosen
# host wins no matter what the values file says. The list is deliberately explicit — it
# documents exactly which values follow the host.
HOST_OVERRIDES=(
  --set "global.host=${HOST}"
  --set "catenax-profile.issuer.did=did:web:issuer.${HOST}:issuer"
  --set "onboarding-api.httpRoute.hostnames={${HOST}}"
  --set-string "onboarding-api.config.participant.did.template=did:web:identity.${HOST}:"
  # The hub resolves member DIDs by the same rule the onboarding-api does; both must follow the host.
  --set-string "membership-hub.config.participant.did.template=did:web:identity.${HOST}:"
  --set "membership-hub.httpRoute.hostnames={${HOST}}"
  --set "certo.gateway.hostnames={${HOST}}"
  # NOTE certo.sigletBaseUrl is deliberately NOT host-derived: certo calls siglet without a
  # bearer token, so it must use the in-cluster siglet service (the checked-in default) — the
  # gateway path sits behind clearglass, which 401s every unauthenticated /tokens/* call.
)

# Trace the actual work (kept off during argument parsing / help output)
set -x

# Traefik: chart repo added idempotently (a VPS operator machine may not have it yet)
helm repo add traefik https://traefik.github.io/charts --force-update
helm upgrade --install --namespace traefik traefik traefik/traefik --create-namespace -f traefik-values.yaml

kubectl rollout status deployment/traefik -n traefik --timeout=120s
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.5.1/standard-install.yaml

# Resolve the umbrella's dependencies (platform, catenax-profile, certo from OCI; the local
# onboarding-api and membership-hub charts are vendored from ../onboarding-api / ../membership-hub)
helm dependency update "$UMBRELLA_CHART"

# The whole VE as one release. Post-install hooks run all seeding in a single ordered hook
# space: platform seeds (weights 10/20) -> catenax-profile (110-130) -> onboarding-api jwtlet
# mapping (200) -> certo jwtlet mappings (210) -> certo activity/orchestration (220) ->
# membership-hub jwtlet mapping (230). The seed hooks dereference gateway-hostname URLs while
# the install is still running — on a VPS that works without CoreDNS patching because <host>
# resolves through public DNS to the VPS itself.
helm upgrade --install "$RELEASE" "$UMBRELLA_CHART" \
  --namespace "$NAMESPACE" --create-namespace \
  "${HOST_OVERRIDES[@]}" \
  --wait --timeout 20m

{ set +x; } 2>/dev/null

# Verify from OUTSIDE the cluster (this machine, through public DNS and the gateway) that the
# issuer's DID document is served and carries the expected id — resolution exactly as an
# external dataspace solution performs it. The IssuerService reconstructs the DID from the
# request URL and looks it up by exact match, so a mismatch means the DNS record, the route or
# the DID itself is wrong. A failure here stops the install rather than surfacing later as a
# credential-verification error during onboarding.
ISSUER_DID="did:web:issuer.${HOST}:issuer"
DID_URL="http://issuer.${HOST}/issuer/did.json"
echo ">> verifying DID resolution from outside the cluster: ${ISSUER_DID}"
echo "   GET $DID_URL"
BODY=""
for attempt in $(seq 1 12); do
  BODY=$(curl -sf --max-time 30 "$DID_URL" || true)
  printf '%s' "$BODY" | grep -qF "\"id\":\"${ISSUER_DID}\"" && break
  [[ "$attempt" -lt 12 ]] && { echo "   ...  document not served yet, retrying ($attempt/12)"; sleep 5; }
done
if printf '%s' "$BODY" | grep -qF "\"id\":\"${ISSUER_DID}\""; then
  echo "   OK   document id matches ${ISSUER_DID}"
else
  echo "   FAIL expected \"id\":\"${ISSUER_DID}\" in the response body:" >&2
  printf '%s\n' "$BODY" >&2
  echo "   Check that issuer.${HOST} resolves to the VPS and port 80 reaches Traefik." >&2
  exit 1
fi

echo
echo "VE installed on the VPS cluster (release $RELEASE, namespace $NAMESPACE, host $HOST)."
