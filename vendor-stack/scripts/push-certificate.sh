#!/bin/bash

# Pushes a certificate to the VE's verification participant — the vendor's half of the CX-0135
# v3.0.0 Flow B exchange a verification run waits for (AWAIT_PUBLISHED_CERTIFICATE), and its
# Checkpoint 3 obligation from docs/sut-verification.md:
#
#   1. resolves the verification participant's DID document for its DSP endpoint;
#   2. requests its catalog until the inbox offer shows up. The offer is credential-gated, so it
#      stays invisible until the vendor participant holds the credentials the VE offered it — this
#      wait doubles as the wait for credential delivery;
#   3. negotiates the offer (mirrored verbatim) and starts an HttpData-PULL transfer: the push flow;
#   4. uploads a document and a certificate into the vendor's Certo tenant and publishes it to the
#      verification participant over that flow, retried until Certo reports the consumer notified.
#
# Run it ONCE per verification run, after the run has onboarded the DID: the VE fails a run that
# finds more than one exchange awaiting acceptance on its verification participant. (Order relative
# to ESTABLISH_PULL_FLOW does not matter — the run finds the pushed exchange either way.)
#
# Usage:
#   ./vendor-stack/scripts/push-certificate.sh [--vp-did <did>] [--vp-bpn <bpn>]
#                                              [--inbox-asset <id>] [--pdf <file>]
#                                              [-s|--short-name <name>] [-h|--help]
#
#   --vp-did          the VE's verification participant
#                     (default: did:web:identity.cxve.localhost:verification-participant)
#   --vp-bpn          its BPN (default: BPNLVERIFY000001)
#   --inbox-asset     its inbox offer (default: ccm-inbox-verification)
#   --pdf             certificate document (default: the Verification UI's sample document)
#   -s, --short-name  the vendor participant (default: vendor-participant)
#
# Environment: VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT (see lib.sh), CREDENTIALS_TIMEOUT (seconds
#              to wait for the inbox offer to become visible, default 900), TIMEOUT (seconds per
#              negotiation/transfer/publish wait, default 180)

source "$(dirname "$0")/lib.sh"

VP_DID="did:web:identity.cxve.localhost:verification-participant"
VP_BPN=BPNLVERIFY000001
INBOX_ASSET=ccm-inbox-verification
PDF="$(cd "$(dirname "$0")/../.." && pwd)/verification-ui/src/main/resources/certificate-document.pdf"
CREDENTIALS_TIMEOUT="${CREDENTIALS_TIMEOUT:-900}"
TIMEOUT="${TIMEOUT:-180}"
MANAGEMENT_CONTEXT="https://w3id.org/edc/connector/management/v2"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --vp-did|--vp-bpn|--inbox-asset|--pdf|-s|--short-name)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        --vp-did) VP_DID="$2" ;;
        --vp-bpn) VP_BPN="$2" ;;
        --inbox-asset) INBOX_ASSET="$2" ;;
        --pdf) PDF="$2" ;;
        -s|--short-name) PARTICIPANT_SHORT_NAME="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument '$1'" ;;
  esac
done

init base64
[[ -r "$PDF" ]] || die "no certificate document at $PDF"
DID=$(participant_did)
PCID=$(participant_context_of "$DID") || exit 1
[[ -n "$PCID" ]] || die "no participant context for $DID — run create-participant.sh first"
log "vendor participant $DID (context $PCID)"

# ---- 1. the verification participant's DSP endpoint -------------------------------------------
VP_DID_URL=$(did_document_url "$VP_DID")
VP_DSP=$(curl -s -m 10 "$VP_DID_URL" \
  | jq -r '.service[]? | select(.type == "ProtocolEndpoint") | .serviceEndpoint' 2>/dev/null || true)
[[ -n "$VP_DSP" ]] || die "no ProtocolEndpoint in the DID document at $VP_DID_URL — is the verification participant onboarded?"
log "verification participant $VP_DID at $VP_DSP"

# ---- 2. the inbox offer -----------------------------------------------------------------------
CATALOG_REQUEST=$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --arg dsp "$VP_DSP" --arg did "$VP_DID" '{
  "@context": [$ctx], "@type": "CatalogRequest",
  counterPartyAddress: $dsp, counterPartyId: $did, protocol: "cx-neptune"
}')
LAST_HINT=0
inbox_offer_visible() {
  mgmt POST "/participants/$PCID/catalog/request" "$CATALOG_REQUEST"
  if [[ "$HTTP_STATUS" == 200 ]]; then
    CATALOG="$HTTP_BODY"
    OFFER=$(printf '%s' "$CATALOG" | jq -c --arg id "$INBOX_ASSET" '
      .dataset | (if type == "array" then . else [.] end) | map(select(.["@id"] == $id)) | .[0].hasPolicy
      | if type == "array" then .[0] else . end // empty')
    [[ -n "$OFFER" && "$OFFER" != null ]] && return 0
  fi
  if (( $(date +%s) - LAST_HINT >= 30 )); then
    LAST_HINT=$(date +%s)
    if [[ "$HTTP_STATUS" == 200 ]]; then
      log "'$INBOX_ASSET' not in the catalog yet — the offer requires the VE-issued credentials (has the run delivered them? see status.sh)"
    else
      log "catalog request answered HTTP $HTTP_STATUS: $(printf '%s' "$HTTP_BODY" | head -c 300)"
    fi
  fi
  return 1
}
poll "$CREDENTIALS_TIMEOUT" 5 "'$INBOX_ASSET' in the catalog of $VP_DID" inbox_offer_visible
log "inbox offer found: $(printf '%s' "$OFFER" | jq -r '.["@id"]')"

# ---- 3. the push flow -------------------------------------------------------------------------
# The contract request must reproduce the offer EXACTLY, so it is copied verbatim — under the
# catalog's own JSON-LD context, so compacted terms expand back to the same IRIs.
NEGOTIATION=$(jq -n --arg ctx "$MANAGEMENT_CONTEXT" --argjson catalog "$CATALOG" --argjson offer "$OFFER" \
    --arg dsp "$VP_DSP" --arg assigner "$VP_DID" --arg target "$INBOX_ASSET" '{
  "@context": ([$ctx] + ($catalog["@context"] | if type == "array" then . else [.] end)),
  "@type": "ContractRequest",
  counterPartyAddress: $dsp,
  protocol: "cx-neptune",
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
    --arg agreement "$AGREEMENT_ID" --arg dsp "$VP_DSP" '{
  "@context": [$ctx], "@type": "TransferRequest",
  contractId: $agreement, counterPartyAddress: $dsp, protocol: "cx-neptune", transferType: "HttpData-PULL"
}')"
expect_2xx "transfer process"
# On the consumer side the transfer process id IS the Certo flow id.
FLOW_ID=$(printf '%s' "$HTTP_BODY" | jq -r '.["@id"]')
poll "$TIMEOUT" 3 "transfer $FLOW_ID to start" reaches transferprocesses "$FLOW_ID" STARTED
log "push flow $FLOW_ID STARTED"

# ---- 4. publish -------------------------------------------------------------------------------
# The certificate's holder is the vendor participant: its BPN as provisioned (cfm.issuer.bpn).
PROFILE=$(participant_profile_of "$DID") || exit 1
BPN=$(printf '%s' "$PROFILE" | jq -r '
  ([.vpas[]? | select(.type == "cfm.issuer") | .properties.bpn] + [.vpaProperties["cfm.issuer"].bpn?])
  | map(select(. != null)) | .[0] // empty')
[[ -n "$BPN" ]] || die "could not read the vendor participant's BPN from its participant profile: $PROFILE"

certo POST "/participant-contexts/$PCID/documents" \
  "$(jq -n --arg content "$(base64 < "$PDF" | tr -d '\n')" '{mediaType: "application/pdf", contentBase64: $content}')"
expect_2xx "document upload"
DOCUMENT_ID=$(printf '%s' "$HTTP_BODY" | jq -r .documentId)
log "document $DOCUMENT_ID uploaded"

certo POST "/participant-contexts/$PCID/certificates" "$(jq -n --arg bpn "$BPN" --arg doc "$DOCUMENT_ID" \
    --arg reg "vendor-$(date +%s)" '{
  certificateType: "ISO9001",
  certificateTypeVersion: "2015",
  registrationNumber: $reg,
  validFrom: "2026-01-01",
  validUntil: "2030-01-01",
  trustLevel: "high",
  certifiedLocations: [{bpnl: $bpn, bpna: "BPNA00000000MAIN0", locationRole: "MAIN_LOCATION"}],
  issuer: {issuerName: "Vendor stack sample CA", issuerBpn: "BPNL00000000ISSUER"},
  documentIds: [$doc]
}')"
expect_2xx "certificate issuance"
CERTIFICATE_ID=$(printf '%s' "$HTTP_BODY" | jq -r .certificateId)
log "certificate $CERTIFICATE_ID issued (holder $BPN)"

# A stable idempotency key makes a repeat reuse the same exchange and only re-notify.
PUBLISH=$(jq -n --arg bpn "$VP_BPN" --arg did "$VP_DID" --arg flow "$FLOW_ID" --arg key "vendor-$CERTIFICATE_ID" '{
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
  certificate:  $CERTIFICATE_ID (ISO9001, holder $BPN)
  exchange:     $EXCHANGE_ID
  push flow:    $FLOW_ID (agreement $AGREEMENT_ID)

The verification run picks it up at AWAIT_PUBLISHED_CERTIFICATE.
EOF
