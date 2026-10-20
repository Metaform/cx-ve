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
# Idempotent: the DID follows from the short name alone, and an already provisioned participant with
# that DID is reused, not re-created — a rerun with the same arguments succeeds without changing
# anything. A rerun that passes a different -n or -m for an existing DID fails, since neither can
# change an existing participant; a different -s/--short-name creates another participant.
#
# Usage:
#   ./vendor-stack/scripts/create-participant.sh [-d|--dataspace <dataspace>] [-n|--name <name>]
#                                                [-s|--short-name <name>] [-m|--member-id <id>]
#                                                [-h|--help]
#
#   -d, --dataspace    catena-x (default) or decade-x — the dataspace of the stack (see install.sh)
#   -n, --name         company name (default: "Vendor Participant")
#   -s, --short-name   last DID segment (default: vendor-participant) — it alone identifies the
#                      participant: a new short name creates another one, an existing one is reused
#   -m, --member-id    the participant's member id in the dataspace — its BPNL in Catena-X (default
#                      BPNLVENDOR000001; -b/--bpn is an alias). Enter the SAME id on the VE's run
#                      form: the VE issues the member credential for the id it is given
#                      (BpnCredential), and Certo on both sides checks certificates against the id in
#                      that credential. DECADE-X assigns the DECADE-X-ID itself when it approves the
#                      onboarding, and the run form takes none; the id given here (default
#                      DX-00009001) only provisions the Certo tenant, and push-certificate.sh uses the
#                      assigned one from the DX_LegalEntity_VC.
#
# Environment: VENDOR_DATASPACE, VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh), TIMEOUT
# (seconds, default 600)

source "$(dirname "$0")/lib.sh"

NAME="Vendor Participant"
NAME_GIVEN=false
MEMBER_ID=""
REUSED=false
TIMEOUT="${TIMEOUT:-600}"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--dataspace|-n|--name|-s|--short-name|-m|--member-id|-b|--bpn)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -d|--dataspace) use_dataspace "$2" ;;
        -n|--name) NAME="$2"; NAME_GIVEN=true ;;
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
        -m|--member-id|-b|--bpn) MEMBER_ID="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

MEMBER_ID_GIVEN=$([[ -n "$MEMBER_ID" ]] && echo true || echo false)
MEMBER_ID="${MEMBER_ID:-$DEFAULT_MEMBER_ID}"
init
DID=$(participant_did)

EXISTING=$(participant_profile_of "$DID") || exit 1
if [[ -n "$EXISTING" ]]; then
  REUSED=true
  TENANT_ID=$(printf '%s' "$EXISTING" | jq -r .tenantId)
  PROFILE_ID=$(printf '%s' "$EXISTING" | jq -r .id)
  # The name is the tenant's, the member id the one the profile's cfm.issuer VPA was deployed with.
  tm GET "/tenants/$TENANT_ID"
  expect_2xx "reading tenant $TENANT_ID"
  EXISTING_NAME=$(printf '%s' "$HTTP_BODY" | jq -r '.properties.name // empty')
  EXISTING_MEMBER_ID=$(printf '%s' "$EXISTING" | jq -r '.vpas[]? | select(.type == "cfm.issuer") | .properties.bpn // empty')
  # -n and -m cannot change an existing participant: a different value means another participant
  # was meant, which only a different short name creates.
  if [[ "$NAME_GIVEN" == true && "$NAME" != "$EXISTING_NAME" ]]; then
    die "$DID already exists, named '$EXISTING_NAME' — -n '$NAME' cannot rename it. Pass a different -s/--short-name to create another participant."
  fi
  if [[ "$MEMBER_ID_GIVEN" == true && "$MEMBER_ID" != "$EXISTING_MEMBER_ID" ]]; then
    die "$DID already exists with $MEMBER_ID_LABEL $EXISTING_MEMBER_ID — -m '$MEMBER_ID' cannot change it. Pass a different -s/--short-name to create another participant."
  fi
  NAME="${EXISTING_NAME:-$NAME}"
  MEMBER_ID="${EXISTING_MEMBER_ID:-$MEMBER_ID}"
  log "$DID already exists (tenant $TENANT_ID, profile $PROFILE_ID) — reusing it, nothing is provisioned"
else
  log "creating tenant '$NAME'"
  tm POST /tenants "$(jq -n --arg name "$NAME" '{properties: {name: $name}}')"
  expect_2xx "tenant creation"
  TENANT_ID=$(printf '%s' "$HTTP_BODY" | jq -r .id)

  # The certo activity reads the member id from cfm.issuer (under `bpn`, whatever the dataspace —
  # the VE's Membership Hub deploys its members the same way); the ccm mapping makes Certo's
  # protocol API the endpoint of the participant's pull flows, with the counterparty's member id
  # stamped into the flow token from its member credential (certo requires it, as `bpn`). The
  # endpoint is counterparty-facing, hence the external address. The transfer type is the Data
  # Plane Signaling HTTP transfer profile's pull value, which the DataAddress endpointType MUST equal.
  case "$VENDOR_DATASPACE" in
    catena-x)
      ISSUER_PROPERTIES=$(jq -n --arg did "$DID" --arg id "$MEMBER_ID" \
        '{id: $did, contractVersion: "1.0", memberOf: "Catena-X", bpn: $id}')
      FLOW_CLAIM="flow.claims.vc.withType('BpnCredential').claim('bpn')"
      ;;
    decade-x)
      ISSUER_PROPERTIES=$(jq -n --arg did "$DID" --arg id "$MEMBER_ID" \
        '{id: $did, memberOf: "DECADE-X", decadeXId: $id, bpn: $id}')
      FLOW_CLAIM="flow.claims.vc.withType('dx:DX_LegalEntity_VC').claim('partner.partnerID')"
      ;;
  esac
  PROFILE=$(jq -n --arg did "$DID" --argjson issuer "$ISSUER_PROPERTIES" --arg claim "$FLOW_CLAIM" \
      --arg ccm "$VENDOR_URL/api/certo" --arg tt "$TRANSFER_TYPE" --arg profile "$DSP_PROFILE" '{
    identifier: $did,
    properties: {},
    vpaProperties: {
      "cfm.issuer": $issuer,
      "cfm.dataplane": {
        authorization: {type: "oauth2_token_exchange"},
        transferTypeMappings: {
          ($tt): {
            transferType: $tt, endpointType: $tt, endpoint: $ccm, tokenSource: "provider",
            claimMappings: [{from: $claim, to: "bpn"}]
          }
        }
      },
      "cfm.connector": {dataspaceProfiles: [$profile]}
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

if [[ "$REUSED" == true ]]; then
  HEADLINE="Vendor participant already existed ($VENDOR_DATASPACE) — reused, nothing was provisioned."
else
  HEADLINE="Vendor participant provisioned ($VENDOR_DATASPACE)."
fi

cat <<EOF

$HEADLINE
  DID:                  $DID
  $(printf '%-21s' "$MEMBER_ID_LABEL:") $MEMBER_ID$([[ "$VENDOR_DATASPACE" == decade-x ]] && echo " (provisional — the TSP assigns it on approval)")
  participant context:  $PCID
  DID document:         $DID_URL
  DSP endpoint:         $DSP

The short name '$PARTICIPANT_SHORT_NAME' identifies this participant; a different -s/--short-name creates another one.
$(if [[ "$VENDOR_DATASPACE" == decade-x ]]; then
  echo "Enter the DID on the VE's run form (DECADE-X assigns the $MEMBER_ID_LABEL on approval), then run seed-ccm-offer.sh."
else
  echo "Enter the DID and the $MEMBER_ID_LABEL on the VE's run form, then run seed-ccm-offer.sh."
fi)
EOF
