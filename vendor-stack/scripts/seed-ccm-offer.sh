#!/bin/bash

# Seeds the vendor participant's certificate offer: the asset the VE's verification participant
# consumes to establish its pull flow to this stack's Certo (CX-0135 Flow B — the consumer
# retrieves the certificate over it and reports its acceptance back). This is the vendor's
# Checkpoint 2 obligation from docs/sut-verification.md.
#
# The asset declares the CX-0135 provider API (dct:type cx-taxo:CCMAPI, dct:subject
# cx-taxo:CompanyCertificateManagementProviderApi, cx-common:version 3.0) — that is what the VE finds
# the offer by. Its id is free; CX-0135 allows only one such offer per business partner, and a VE run
# fails when the catalog offers the same API twice.
#
# The policies mirror the offers the VE itself makes (verification-ui's
# VerificationParticipantService): catalog visibility requires a Catena-X membership, a contract
# requires the data exchange governance framework agreement plus the usage purpose and usage end
# definitions. The Catena-X profile's CEL expressions evaluate them against the consumer's
# credentials — which, for the VE's verification participant, the VE's issuer signed and this
# stack trusts.
#
# Idempotent: policies and contract definition are kept when they exist; the asset is written
# through, so its API properties are always current.
#
# Usage:
#   ./vendor-stack/scripts/seed-ccm-offer.sh [-s|--short-name <name>] [--asset <id>] [-h|--help]
#
#   -s, --short-name   the vendor participant (default: vendor-participant)
#   --asset            asset id (default: <short name>-ccm-provider-api)
#
# Environment: VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh), CCM_API_VERSION (default 3.0)

source "$(dirname "$0")/lib.sh"

ASSET_ID=""
MANAGEMENT_CONTEXT="https://w3id.org/edc/connector/management/v2"
CX_POLICY_CONTEXT="https://w3id.org/catenax/2025/9/policy/context.jsonld"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--short-name|--asset)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
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

# constraint <leftOperand> <operator> <rightOperand> -> ODRL constraint (compacted, CX policy context)
constraint() {
  jq -n --arg l "$1" --arg o "$2" --arg r "$3" '{leftOperand: $l, operator: $o, rightOperand: $r}'
}

policy() { # <id> <action> <constraint-json...>
  local id="$1" action="$2"
  shift 2
  jq -n --arg mctx "$MANAGEMENT_CONTEXT" --arg cxctx "$CX_POLICY_CONTEXT" --arg id "$id" \
        --arg action "$action" --argjson constraints "$(printf '%s\n' "$@" | jq -s .)" '{
    "@context": [$mctx, $cxctx],
    "@type": "PolicyDefinition",
    "@id": $id,
    "policy": {
      "@type": "Set",
      "permission": [{action: $action, constraint: [{and: $constraints}]}]
    }
  }'
}

ACCESS_POLICY_ID="${PARTICIPANT_SHORT_NAME}-ccm-access-policy"
CONTRACT_POLICY_ID="${PARTICIPANT_SHORT_NAME}-ccm-contract-policy"
CONTRACT_DEFINITION_ID="${PARTICIPANT_SHORT_NAME}-ccm-cd"

# The asset declares the CX-0135 provider API and carries no address: under Data Plane Signaling
# the data plane owns the endpoint — the participant's transfer-type mapping points the pull flows
# at Certo's protocol API, and the data plane hands it to the consumer as the DataAddress of the
# started transfer.
ASSET=$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --arg id "$ASSET_ID" --argjson api "$(ccm_api_properties "$CCM_PROVIDER_API")" '{
  "@context": [$ctx],
  "@type": "Asset",
  "@id": $id,
  "properties": ($api + {"http://purl.org/dc/terms/description": "vendor CCM provider API (CX-0135)"})
}')
mgmt POST "/participants/$PCID/assets" "$ASSET"
if [[ "$HTTP_STATUS" == 409 ]]; then
  mgmt GET "/participants/$PCID/assets/$ASSET_ID"
  [[ "$HTTP_STATUS" == 200 ]] || die "asset id '$ASSET_ID' is taken by another participant on this control plane — pass --asset"
  mgmt PUT "/participants/$PCID/assets" "$ASSET"
  expect_2xx "updating asset '$ASSET_ID'"
  log "asset '$ASSET_ID' updated (CX-0135 provider API $CCM_API_VERSION)"
else
  expect_2xx "creating asset '$ASSET_ID'"
  log "asset '$ASSET_ID' created (CX-0135 provider API $CCM_API_VERSION)"
fi

create_once "access policy" policydefinitions "$ACCESS_POLICY_ID" "$(policy "$ACCESS_POLICY_ID" access \
  "$(constraint Membership eq active)")"

create_once "contract policy" policydefinitions "$CONTRACT_POLICY_ID" "$(policy "$CONTRACT_POLICY_ID" use \
  "$(constraint FrameworkAgreement eq DataExchangeGovernance:1.0)" \
  "$(constraint UsagePurpose isAnyOf cx.pcf.base:1)" \
  "$(constraint DataUsageEndDefinition eq cx.dataUsageEnd.unlimited:1)")"

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
echo "Offer '$ASSET_ID' seeded on $DID as the CX-0135 provider API $CCM_API_VERSION — the VE's"
echo "ESTABLISH_PULL_FLOW step finds it by that."
