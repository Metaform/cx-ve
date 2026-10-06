#!/bin/bash

# Pushes a certificate to the VE's verification participant — the vendor's half of the CX-0135
# v3.0.0 Flow B exchange a verification run waits for (AWAIT_PUBLISHED_CERTIFICATE), and its
# Checkpoint 3 obligation from docs/sut-verification.md:
#
#   1. resolves the verification participant's DID document for its DSP endpoint — under the
#      stack's DSP profile: a DID document advertises one profile per platform (the VE's:
#      cx-neptune), so for DECADE-X the endpoint's profile segment is replaced with decade-x;
#   2. waits until the vendor participant holds its member credential from the VE (Catena-X:
#      MembershipCredential, DECADE-X: DecadeXMembershipCredential) — every DSP message of the
#      profile presents it — then requests the catalog until the inbox offer shows up: the dataset
#      declaring the CX-0135 consumer API (dct:subject
#      cx-taxo:CompanyCertificateManagementConsumerApi, cx-common:version 3.0), whatever its id;
#   3. negotiates the offer (mirrored verbatim) and starts a pull transfer (Data Plane Signaling
#      http-pull profile): the push flow;
#   4. uploads a document and a certificate into the vendor's Certo tenant and publishes it to the
#      verification participant over that flow, retried until Certo reports the consumer notified.
#
# Run it ONCE per verification run, after the run has onboarded the DID: the VE fails a run that
# finds more than one exchange awaiting acceptance on its verification participant. (Order relative
# to ESTABLISH_PULL_FLOW does not matter — the run finds the pushed exchange either way.)
#
# Usage:
#   ./vendor-stack/scripts/push-certificate.sh [-d|--dataspace <dataspace>] [--vp-did <did>]
#                                              [--vp-member-id <id>] [--pdf <file>]
#                                              [-s|--short-name <name>] [-h|--help]
#
#   -d, --dataspace     catena-x (default) or decade-x — the dataspace of the stack
#   --vp-did            the VE's verification participant of the dataspace (default:
#                       did:web:identity.cxve.localhost:verification-participant, -dx in DECADE-X)
#   --vp-member-id      its member id (default: BPNLVERIFY000001, DX-99999999 in DECADE-X;
#                       --vp-bpn is an alias)
#   --pdf               certificate document (default: the Verification UI's sample document)
#   -s, --short-name    the vendor participant (default: vendor-participant)
#
# Environment: VENDOR_DATASPACE, VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT, VE_HOST, CCM_API_VERSION
#              (see lib.sh), CREDENTIALS_TIMEOUT (seconds to wait for the member credential and the
#              inbox offer, default 900), TIMEOUT (seconds per negotiation/transfer/publish wait,
#              default 180)

source "$(dirname "$0")/lib.sh"

VP_DID_ARG=""
VP_MEMBER_ID_ARG=""
PDF="$(cd "$(dirname "$0")/../.." && pwd)/verification-ui/src/main/resources/certificate-document.pdf"
CREDENTIALS_TIMEOUT="${CREDENTIALS_TIMEOUT:-900}"
TIMEOUT="${TIMEOUT:-180}"
MANAGEMENT_CONTEXT="https://w3id.org/edc/connector/management/v2"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--dataspace|--vp-did|--vp-member-id|--vp-bpn|--pdf|-s|--short-name)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -d|--dataspace) use_dataspace "$2" ;;
        --vp-did) VP_DID_ARG="$2" ;;
        --vp-member-id|--vp-bpn) VP_MEMBER_ID_ARG="$2" ;;
        --pdf) PDF="$2" ;;
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

# the dataspace's verification participant (lib.sh), unless given explicitly
VP_DID="${VP_DID_ARG:-$VP_DID}"
VP_MEMBER_ID="${VP_MEMBER_ID_ARG:-$VP_MEMBER_ID}"
case "$VENDOR_DATASPACE" in
  catena-x) SITE_ID=BPNA00000000MAIN0; ISSUER_ID=BPNL00000000ISSUER ;;
  # DECADE-X-style ids, as the VE's own DECADE-X sample certificate carries them
  decade-x) SITE_ID=DX-00000000-MAIN; ISSUER_ID=DX-00000000 ;;
esac

init base64
[[ -r "$PDF" ]] || die "no certificate document at $PDF"
DID=$(participant_did)
PCID=$(participant_context_of "$DID") || exit 1
[[ -n "$PCID" ]] || die "no participant context for $DID — run create-participant.sh first"
log "vendor participant $DID (context $PCID)"

# ---- 1. the verification participant's DSP endpoint -------------------------------------------
VP_DID_URL=$(did_document_url "$VP_DID")
ADVERTISED_DSP=$(curl -s -m 10 "$VP_DID_URL" \
  | jq -r '.service[]? | select(.type == "ProtocolEndpoint") | .serviceEndpoint' 2>/dev/null || true)
[[ -n "$ADVERTISED_DSP" ]] || die "no ProtocolEndpoint in the DID document at $VP_DID_URL — is the verification participant onboarded?"
VP_DSP=$(dsp_endpoint_for_profile "$ADVERTISED_DSP")
log "verification participant $VP_DID at $VP_DSP"

# ---- 2. the member credential, then the inbox offer -------------------------------------------
LAST_HINT=0
holds_member_credential() {
  [[ -n "$(member_credential_of "$PCID")" ]] && return 0
  if (( $(date +%s) - LAST_HINT >= 30 )); then
    LAST_HINT=$(date +%s)
    log "no $MEMBER_CREDENTIAL in the wallet yet — has the run onboarded $DID? (status.sh)"
  fi
  return 1
}
poll "$CREDENTIALS_TIMEOUT" 5 "the $MEMBER_CREDENTIAL from the VE" holds_member_credential
log "$MEMBER_CREDENTIAL received"

CATALOG_REQUEST=$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --arg dsp "$VP_DSP" --arg did "$VP_DID" --arg profile "$DSP_PROFILE" '{
  "@context": [$ctx], "@type": "CatalogRequest",
  counterPartyAddress: $dsp, counterPartyId: $did, protocol: $profile
}')
LAST_HINT=0
inbox_offer_visible() {
  mgmt POST "/participants/$PCID/catalog/request" "$CATALOG_REQUEST"
  if [[ "$HTTP_STATUS" == 200 ]]; then
    CATALOG="$HTTP_BODY"
    local matches count
    matches=$(printf '%s' "$CATALOG" | ccm_api_datasets "$CCM_CONSUMER_API")
    count=$(printf '%s' "$matches" | jq 'length')
    if (( count > 1 )); then
      die "the verification participant offers the CX-0135 consumer API $CCM_API_VERSION $count times ($(printf '%s' "$matches" | jq -r 'map(.["@id"]) | join(", ")')) — ambiguous"
    fi
    if (( count == 1 )); then
      INBOX_ASSET=$(printf '%s' "$matches" | jq -r '.[0]["@id"]')
      OFFER=$(printf '%s' "$matches" | jq -c '.[0].hasPolicy | if type == "array" then .[0] else . end // empty')
      [[ -n "$OFFER" && "$OFFER" != null ]] && return 0
    fi
  fi
  if (( $(date +%s) - LAST_HINT >= 30 )); then
    LAST_HINT=$(date +%s)
    if [[ "$HTTP_STATUS" == 200 ]]; then
      log "no CX-0135 consumer API offer in the catalog yet (it offers: $(printf '%s' "$CATALOG" | jq -r '[.dataset | (if type == "array" then . elif . == null then [] else [.] end) | .[]["@id"]] | if length == 0 then "nothing" else join(", ") end')) — has the run ensured the verification participant?"
    else
      log "catalog request answered HTTP $HTTP_STATUS: $(printf '%s' "$HTTP_BODY" | head -c 300)"
    fi
  fi
  return 1
}
poll "$CREDENTIALS_TIMEOUT" 5 "the CX-0135 consumer API offer in the catalog of $VP_DID" inbox_offer_visible
log "inbox offer found: dataset '$INBOX_ASSET', offer $(printf '%s' "$OFFER" | jq -r '.["@id"]')"

# ---- 3. the push flow -------------------------------------------------------------------------
# The contract request must reproduce the offer EXACTLY, so it is copied verbatim — under the
# catalog's own JSON-LD context, so compacted terms expand back to the same IRIs.
NEGOTIATION=$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --argjson catalog "$CATALOG" --argjson offer "$OFFER" \
    --arg dsp "$VP_DSP" --arg assigner "$VP_DID" --arg target "$INBOX_ASSET" --arg profile "$DSP_PROFILE" '{
  "@context": ([$ctx] + ($catalog["@context"] | if type == "array" then . else [.] end)),
  "@type": "ContractRequest",
  counterPartyAddress: $dsp,
  protocol: $profile,
  policy: ($offer + {assigner: $assigner, target: $target})
}')
mgmt POST "/participants/$PCID/contractnegotiations" "$NEGOTIATION"
expect_2xx "contract negotiation"
NEGOTIATION_ID=$(printf '%s' "$HTTP_BODY" | jq -r '.["@id"]')
log "negotiation $NEGOTIATION_ID started"

# reaches <collection> <id> <state...>: polls the resource's state; TERMINATED fails the script
reaches() {
  local collection="$1" id="$2" state
  shift 2
  mgmt GET "/participants/$PCID/$collection/$id"
  [[ "$HTTP_STATUS" == 200 ]] || return 1
  state=$(printf '%s' "$HTTP_BODY" | jq -r .state)
  [[ "$state" == TERMINATED ]] && die "$collection/$id TERMINATED: $(printf '%s' "$HTTP_BODY" | jq -r '.errorDetail // "no detail"')"
  local wanted
  for wanted in "$@"; do [[ "$state" == "$wanted" ]] && return 0; done
  return 1
}
poll "$TIMEOUT" 3 "negotiation $NEGOTIATION_ID to finalize" reaches contractnegotiations "$NEGOTIATION_ID" FINALIZED
AGREEMENT_ID=$(printf '%s' "$HTTP_BODY" | jq -r .contractAgreementId)
log "negotiation FINALIZED, agreement $AGREEMENT_ID"

mgmt POST "/participants/$PCID/transferprocesses" "$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" \
    --arg agreement "$AGREEMENT_ID" --arg dsp "$VP_DSP" --arg tt "$TRANSFER_TYPE" --arg profile "$DSP_PROFILE" '{
  "@context": [$ctx], "@type": "TransferRequest",
  contractId: $agreement, counterPartyAddress: $dsp, protocol: $profile, transferType: $tt
}')"
expect_2xx "transfer process"
# On the consumer side the transfer process id IS the Certo flow id.
FLOW_ID=$(printf '%s' "$HTTP_BODY" | jq -r '.["@id"]')
poll "$TIMEOUT" 3 "transfer $FLOW_ID to start" reaches transferprocesses "$FLOW_ID" STARTED
log "push flow $FLOW_ID STARTED"

# ---- 4. publish -------------------------------------------------------------------------------
# The certificate's holder is the vendor participant: its member id as provisioned (cfm.issuer.bpn —
# Certo's name for the member id, whatever the dataspace).
PROFILE=$(participant_profile_of "$DID") || exit 1
HOLDER=$(printf '%s' "$PROFILE" | jq -r '
  ([.vpas[]? | select(.type == "cfm.issuer") | .properties.bpn] + [.vpaProperties["cfm.issuer"].bpn?])
  | map(select(. != null)) | .[0] // empty')
[[ -n "$HOLDER" ]] || die "could not read the vendor participant's $MEMBER_ID_LABEL from its participant profile: $PROFILE"

certo POST "/participant-contexts/$PCID/documents" \
  "$(jq -n --arg content "$(base64 < "$PDF" | tr -d '\n')" '{mediaType: "application/pdf", contentBase64: $content}')"
expect_2xx "document upload"
DOCUMENT_ID=$(printf '%s' "$HTTP_BODY" | jq -r .documentId)
log "document $DOCUMENT_ID uploaded"

certo POST "/participant-contexts/$PCID/certificates" "$(jq -n --arg bpn "$HOLDER" --arg doc "$DOCUMENT_ID" \
    --arg site "$SITE_ID" --arg issuer "$ISSUER_ID" --arg reg "vendor-$(date +%s)" '{
  certificateType: "ISO9001",
  certificateTypeVersion: "2015",
  registrationNumber: $reg,
  validFrom: "2026-01-01",
  validUntil: "2030-01-01",
  trustLevel: "high",
  certifiedLocations: [{bpnl: $bpn, bpna: $site, locationRole: "MAIN_LOCATION"}],
  issuer: {issuerName: "Vendor stack sample CA", issuerBpn: $issuer},
  documentIds: [$doc]
}')"
expect_2xx "certificate issuance"
CERTIFICATE_ID=$(printf '%s' "$HTTP_BODY" | jq -r .certificateId)
log "certificate $CERTIFICATE_ID issued (holder $HOLDER)"

# A stable idempotency key makes a repeat reuse the same exchange and only re-notify.
PUBLISH=$(jq -n --arg bpn "$VP_MEMBER_ID" --arg did "$VP_DID" --arg flow "$FLOW_ID" --arg key "vendor-$CERTIFICATE_ID" '{
  consumerBpn: $bpn, consumerDid: $did, flowId: $flow, idempotencyKey: $key,
  protocolVersion: "3.0.0", embedded: false
}')
published() {
  certo POST "/participant-contexts/$PCID/certificates/$CERTIFICATE_ID/publish" "$PUBLISH"
  if [[ "$HTTP_STATUS" =~ ^4 ]]; then
    die "publish rejected with HTTP $HTTP_STATUS: $HTTP_BODY"
  fi
  if [[ "$HTTP_STATUS" =~ ^2 ]] && [[ "$(printf '%s' "$HTTP_BODY" | jq -r .consumerNotified)" == true ]]; then
    return 0
  fi
  log "publish not delivered yet (HTTP $HTTP_STATUS): $(printf '%s' "$HTTP_BODY" | head -c 300)"
  return 1
}
poll "$TIMEOUT" 5 "certificate $CERTIFICATE_ID to reach the verification participant" published
EXCHANGE_ID=$(printf '%s' "$HTTP_BODY" | jq -r .exchangeId)

cat <<EOF

Certificate pushed to the verification participant.
  certificate:  $CERTIFICATE_ID (ISO9001, holder $HOLDER)
  exchange:     $EXCHANGE_ID
  push flow:    $FLOW_ID (agreement $AGREEMENT_ID)

The verification run picks it up at AWAIT_PUBLISHED_CERTIFICATE.
EOF
