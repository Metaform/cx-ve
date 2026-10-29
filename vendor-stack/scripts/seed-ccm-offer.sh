#!/bin/bash

# Seeds the vendor participant's certificate offer: the asset the VE's verification participant
# consumes to establish its pull flow to this stack's Certo (DSAF SCE Certificate Provider API — the
# consumer requests the certificate over it, retrieves it and reports its acceptance back). This is
# the vendor's Checkpoint 2 obligation from docs/sut-verification.md.
#
# The asset declares the SCE provider API (dct:conformsTo https://w3id.org/dsaf/sce/v1/provider-api)
# — that is what the VE finds the offer by. Its id is free; SCE has a participant publish one
# dataset per API, and a VE run fails when the catalog offers the same API twice.
#
# The policies mirror the offers the VE itself makes in the stack's dataspace (verification-ui's
# verification.dataspaces.<id>). DECADE-X has no policy vocabulary yet: its offers are
# unconstrained, as the VE's are — the access policy an empty Set (a rule without constraints is refused for `access`, which is bound to
# no scope), the contract policy one `use` permission without constraints (a contract request needs
# a rule; `use` is bound). Its DSP profile's DCP scope still requires the consumer to present its
# DX_LegalEntity_VC.
#
# Idempotent: policies and contract definition are kept when they exist; the asset is written
# through, so its API properties are always current.
#
# Usage:
#   ./vendor-stack/scripts/seed-ccm-offer.sh [-d|--dataspace <dataspace>] [-s|--short-name <name>]
#                                            [--asset <id>] [-h|--help]
#
#   -d, --dataspace    decade-x (default, the only one so far) — the dataspace of the stack
#   -s, --short-name   the vendor participant (default: vendor-participant)
#   --asset            asset id (default: <short name>-ccm-provider-api)
#
# Environment: VENDOR_DATASPACE, VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh)

source "$(dirname "$0")/lib.sh"

ASSET_ID=""
MANAGEMENT_CONTEXT="https://w3id.org/edc/connector/management/v2"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--dataspace|-s|--short-name|--asset)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -d|--dataspace) use_dataspace "$2" ;;
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
        --asset) ASSET_ID="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

init
DID=$(participant_did)
PCID=$(participant_context_of "$DID") || exit 1
[[ -n "$PCID" ]] || die "no participant context for $DID — run create-participant.sh first"
log "vendor participant $DID (context $PCID)"
# Every id is the participant's own: EDC ids are unique across all participant contexts.
ASSET_ID="${ASSET_ID:-${PARTICIPANT_SHORT_NAME}-ccm-provider-api}"

# create_once <what> <resource-collection> <id> <json>: GET by id on THIS participant, POST when
# absent. EDC ids are unique across all participant contexts of the control plane, so a 409 on
# the POST — after the GET found nothing here — means another participant holds the id, and this
# participant is left without the object.
create_once() {
  local what="$1" collection="$2" id="$3" body="$4"
  mgmt GET "/participants/$PCID/$collection/$id"
  if [[ "$HTTP_STATUS" == 200 ]]; then
    log "$what '$id' already exists"
    return
  fi
  mgmt POST "/participants/$PCID/$collection" "$body"
  if [[ "$HTTP_STATUS" == 409 ]]; then
    die "$what id '$id' is taken by another participant on this control plane — the stack hosts one vendor participant (see README, Caveats)"
  fi
  expect_2xx "creating $what '$id'"
  log "$what '$id' created"
}

# An unconstrained policy: for `access` an empty Set, for `use` one permission without constraints
# (see the header).
open_policy() { # <id> <action>
  jq -n --arg mctx "$MANAGEMENT_CONTEXT" --arg id "$1" --arg action "$2" '{
    "@context": [$mctx],
    "@type": "PolicyDefinition",
    "@id": $id,
    "policy": ({"@type": "Set"} + (if $action == "access" then {} else {permission: [{action: $action}]} end))
  }'
}

ACCESS_POLICY_ID="${PARTICIPANT_SHORT_NAME}-ccm-access-policy"
CONTRACT_POLICY_ID="${PARTICIPANT_SHORT_NAME}-ccm-contract-policy"
CONTRACT_DEFINITION_ID="${PARTICIPANT_SHORT_NAME}-ccm-cd"

# The asset declares the SCE provider API and carries no address: under Data Plane Signaling
# the data plane owns the endpoint — the participant's transfer-type mapping points the pull flows
# at Certo's protocol API, and the data plane hands it to the consumer as the DataAddress of the
# started transfer.
ASSET=$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --arg id "$ASSET_ID" --argjson api "$(sce_api_properties "$SCE_PROVIDER_API")" '{
  "@context": [$ctx],
  "@type": "Asset",
  "@id": $id,
  "properties": ($api + {"http://purl.org/dc/terms/description": "vendor certificate provider API (DSAF SCE)"})
}')
mgmt POST "/participants/$PCID/assets" "$ASSET"
if [[ "$HTTP_STATUS" == 409 ]]; then
  mgmt GET "/participants/$PCID/assets/$ASSET_ID"
  [[ "$HTTP_STATUS" == 200 ]] || die "asset id '$ASSET_ID' is taken by another participant on this control plane — pass --asset"
  mgmt PUT "/participants/$PCID/assets" "$ASSET"
  expect_2xx "updating asset '$ASSET_ID'"
  log "asset '$ASSET_ID' updated (SCE provider API)"
else
  expect_2xx "creating asset '$ASSET_ID'"
  log "asset '$ASSET_ID' created (SCE provider API)"
fi

case "$VENDOR_DATASPACE" in
  decade-x)
    create_once "access policy" policydefinitions "$ACCESS_POLICY_ID" "$(open_policy "$ACCESS_POLICY_ID" access)"
    create_once "contract policy" policydefinitions "$CONTRACT_POLICY_ID" "$(open_policy "$CONTRACT_POLICY_ID" use)"
    ;;
esac

create_once "contract definition" contractdefinitions "$CONTRACT_DEFINITION_ID" "$(jq -n \
    --arg ctx "$MANAGEMENT_CONTEXT" --arg id "$CONTRACT_DEFINITION_ID" \
    --arg access "$ACCESS_POLICY_ID" --arg contract "$CONTRACT_POLICY_ID" '{
  "@context": [$ctx],
  "@type": "ContractDefinition",
  "@id": $id,
  accessPolicyId: $access,
  contractPolicyId: $contract,
  assetsSelector: []
}')"

echo
echo "Offer '$ASSET_ID' seeded on $DID as the SCE provider API ($SCE_PROVIDER_API) — the"
echo "VE's ESTABLISH_PULL_FLOW step finds it by that."
