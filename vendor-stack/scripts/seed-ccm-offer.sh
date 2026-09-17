#!/bin/bash

# Seeds the vendor participant's certificate offer: the asset the VE's verification participant
# consumes to establish its pull flow to this stack's Certo (CX-0135 Flow B — the consumer
# retrieves the certificate over it and reports its acceptance back). This is the vendor's
# Checkpoint 2 obligation from docs/sut-verification.md.
#
# The policies mirror the offers the VE itself makes (verification-ui's
# VerificationParticipantService): catalog visibility requires a Catena-X membership, a contract
# requires the data exchange governance framework agreement plus the usage purpose and usage end
# definitions. The Catena-X profile's CEL expressions evaluate them against the consumer's
# credentials — which, for the VE's verification participant, the VE's issuer signed and this
# stack trusts.
#
# Idempotent: existing objects are kept.
#
# Usage:
#   ./vendor-stack/scripts/seed-ccm-offer.sh [-s|--short-name <name>] [--asset <id>] [-h|--help]
#
#   -s, --short-name   the vendor participant (default: vendor-participant)
#   --asset            asset id (default: ccm-api). Change it ONLY together with the VE's
#                      verification.external.provider-asset-id: the verification run looks for
#                      exactly that id in the catalog, and waits out its whole budget for it
#                      when the offer carries any other one.
#
# Environment: VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh)

source "$(dirname "$0")/lib.sh"

ASSET_ID=ccm-api
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
if [[ "$ASSET_ID" != ccm-api ]]; then
  log "WARNING: asset id '$ASSET_ID' — a verification run finds this offer only if the VE's"
  log "         verification.external.provider-asset-id is '$ASSET_ID' too (default: ccm-api)"
fi

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

ACCESS_POLICY_ID="vendor-ccm-access-policy"
CONTRACT_POLICY_ID="vendor-ccm-contract-policy"
CONTRACT_DEFINITION_ID="vendor-ccm-cd"

# The data address is informational for a Siglet-backed flow — the flow's endpoint comes from the
# participant's CCM transfer-type mapping — but it names the same counterparty-facing Certo address.
create_once asset assets "$ASSET_ID" "$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --arg id "$ASSET_ID" \
    --arg url "$VENDOR_URL/api/certo" '{
  "@context": [$ctx],
  "@type": "Asset",
  "@id": $id,
  "properties": {name: "vendor CCM API (CX-0135)"},
  "dataAddress": {"@type": "DataAddress", type: "HttpData", baseUrl: $url}
}')"

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
echo "Offer '$ASSET_ID' seeded on $DID — the VE's ESTABLISH_PULL_FLOW step can now find it."
