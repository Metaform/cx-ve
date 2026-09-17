#!/bin/bash

# Creates the vendor participant: deploys a participant profile to the vendor stack's CFM Tenant
# Manager — the same profile shape the VE's Membership Hub deploys for its own members
# (membership-hub/.../adapter/out/cfm/CfmTenantManager.java) — and waits until it is provisioned:
# control plane and IdentityHub participant contexts, the DID document, the Siglet data plane with
# the CCM transfer-type mapping, and the Certo tenant.
#
# It gets NO credentials here. The stack's orchestration has no credential activities (see
# chart/templates/certo-activity-seed-job.yaml): the credentials come from the VE, which offers
# them once the participant's DID is entered on the Verification UI's run form.
#
# Idempotent: an already provisioned participant with the same DID is reported, not re-created.
#
# Usage:
#   ./vendor-stack/scripts/create-participant.sh [-n|--name <name>] [-s|--short-name <name>]
#                                                [-b|--bpn <bpn>] [-h|--help]
#
#   -n, --name         company name (default: "Vendor Participant")
#   -s, --short-name   last DID segment (default: vendor-participant)
#   -b, --bpn          the participant's BPNL (default: BPNLVENDOR000001). Enter the SAME BPN on the
#                      VE's run form: the VE issues the BpnCredential for the BPN it is given, and
#                      Certo on both sides checks certificates against the BPN in that credential.
#
# Environment: VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh), TIMEOUT (seconds, default 600)

source "$(dirname "$0")/lib.sh"

NAME="Vendor Participant"
BPN=BPNLVENDOR000001
TIMEOUT="${TIMEOUT:-600}"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--name|-s|--short-name|-b|--bpn)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -n|--name) NAME="$2" ;;
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
        -b|--bpn) BPN="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

init
DID=$(participant_did)

EXISTING=$(participant_profile_of "$DID") || exit 1
if [[ -n "$EXISTING" ]]; then
  TENANT_ID=$(printf '%s' "$EXISTING" | jq -r .tenantId)
  PROFILE_ID=$(printf '%s' "$EXISTING" | jq -r .id)
  log "participant profile for $DID already exists (tenant $TENANT_ID, profile $PROFILE_ID)"
else
  log "creating tenant '$NAME'"
  tm POST /tenants "$(jq -n --arg name "$NAME" '{properties: {name: $name}}')"
  expect_2xx "tenant creation"
  TENANT_ID=$(printf '%s' "$HTTP_BODY" | jq -r .id)

  # The certo activity reads the BPN from cfm.issuer; the ccm mapping makes Certo's protocol API
  # the endpoint of the participant's pull flows, with the counterparty's BPN stamped into the flow
  # token from its BpnCredential (certo requires it). The endpoint is counterparty-facing, hence the
  # external address. The transfer type is the Data Plane Signaling HTTP transfer profile's pull
  # value, which the DataAddress endpointType MUST equal.
  PROFILE=$(jq -n --arg did "$DID" --arg bpn "$BPN" --arg ccm "$VENDOR_URL/api/certo" --arg tt "$TRANSFER_TYPE" '{
    identifier: $did,
    properties: {},
    vpaProperties: {
      "cfm.issuer": {id: $did, contractVersion: "1.0", memberOf: "Catena-X", bpn: $bpn},
      "cfm.dataplane": {
        authorization: {type: "oauth2_token_exchange"},
        transferTypeMappings: {
          ($tt): {
            transferType: $tt, endpointType: $tt, endpoint: $ccm, tokenSource: "provider",
            claimMappings: [{from: "flow.claims.vc.withType('"'"'BpnCredential'"'"').claim('"'"'bpn'"'"')", to: "bpn"}]
          }
        }
      },
      "cfm.connector": {dataspaceProfiles: ["cx-neptune"]}
    }
  }')
  log "deploying participant profile for $DID"
  tm POST "/tenants/$TENANT_ID/participant-profiles" "$PROFILE"
  expect_2xx "participant profile deployment"
  PROFILE_ID=$(printf '%s' "$HTTP_BODY" | jq -r .id)
fi

profile_active() {
  tm GET "/tenants/$TENANT_ID/participant-profiles/$PROFILE_ID"
  [[ "$HTTP_STATUS" == 200 ]] || return 1
  [[ "$(printf '%s' "$HTTP_BODY" | jq -r '.error // false')" == true ]] \
    && die "provisioning failed: $(printf '%s' "$HTTP_BODY" | jq -c '{vpas: [.vpas[] | {type, state}], properties}')"
  local states
  states=$(printf '%s' "$HTTP_BODY" | jq -r '[.vpas[] | "\(.type)=\(.state)"] | join(" ")')
  log "VPAs: ${states:-none yet}"
  printf '%s' "$HTTP_BODY" | jq -e '(.vpas | length) > 0 and all(.vpas[]; .state == "active")' >/dev/null
}
poll "$TIMEOUT" 5 "the participant profile's VPAs to become active" profile_active

PCID=$(printf '%s' "$HTTP_BODY" | jq -r '.properties["cfm.vpa.state"].participantContextId // empty')
[[ -n "$PCID" ]] || die "provisioned profile carries no participantContextId: $HTTP_BODY"

certo_tenant_exists() {
  certo GET "/participant-contexts/$PCID"
  [[ "$HTTP_STATUS" == 200 ]]
}
poll 120 3 "the Certo tenant of $PCID" certo_tenant_exists

DID_URL=$(did_document_url "$DID")
DID_DOC=$(curl -s -m 10 "$DID_URL")
DSP=$(printf '%s' "$DID_DOC" | jq -r '.service[]? | select(.type == "ProtocolEndpoint") | .serviceEndpoint' 2>/dev/null || true)
[[ -n "$DSP" ]] || die "the DID document at $DID_URL advertises no ProtocolEndpoint: $DID_DOC"

cat <<EOF

Vendor participant provisioned.
  DID:                  $DID
  BPN:                  $BPN
  participant context:  $PCID
  DID document:         $DID_URL
  DSP endpoint:         $DSP

Enter the DID (and the BPN) on the VE's run form, then run seed-ccm-offer.sh.
EOF
