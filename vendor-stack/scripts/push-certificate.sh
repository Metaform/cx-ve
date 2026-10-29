#!/bin/bash

# Issues the vendor's certificate and hands it to the VE's verification participant — the vendor's
# half of the DSAF SCE exchange a verification run drives (REQUEST_CERTIFICATE), and its Checkpoint
# 3 obligation from docs/sut-verification.md:
#
#   1. resolves the verification participant's DID document for its DSP endpoint, waiting while a
#      fresh VE has not created it yet — under the stack's DSP profile: a DID document advertises
#      one profile per platform, so the endpoint's profile segment is replaced with the stack's
#      (decade-x);
#   2. waits until the vendor participant holds its member credential from the VE
#      (dx:DX_LegalEntity_VC) — every DSP message of the profile presents it, and it carries the
#      DECADE-X-ID the TSP assigned on approval;
#   3. uploads a document and issues an iso9001 certificate in the vendor's Certo tenant, certifying
#      the vendor participant's legal entity under that DECADE-X-ID. From then on Certo fulfils a
#      request for it on arrival — the common case, since the VE requests it only after the
#      credentials are delivered and its pull flow is established;
#   4. requests the catalog until the inbox offer shows up: the dataset declaring the SCE consumer
#      API (dct:conformsTo https://w3id.org/dsaf/sce/v1/consumer-api), whatever its id;
#   5. negotiates the offer (mirrored verbatim) and starts a pull transfer (Data Plane Signaling
#      http-pull profile): the push flow;
#   6. notifies the verification participant over that flow that the certificate is AVAILABLE,
#      retried (same messageId) until Certo reports the consumer notified;
#   7. fulfils every request of the verification participant still waiting for the certificate —
#      one the VE made before step 3 — which pushes FULFILLED to it over the push flow.
#
# Can run any time after create-participant.sh: it waits for the run to deliver the credentials. A
# run needs it at most once — a later run's request is fulfilled from the certificate already held.
# A rerun is harmless: it issues and notifies another certificate, and lifecycle notifications never
# open or change an exchange.
#
# Usage:
#   ./vendor-stack/scripts/push-certificate.sh [-d|--dataspace <dataspace>] [--vp-did <did>]
#                                              [--pdf <file>] [-s|--short-name <name>] [-h|--help]
#
#   -d, --dataspace     decade-x (default, the only one so far) — the dataspace of the stack
#   --vp-did            the VE's verification participant of the dataspace (default:
#                       did:web:identity.cxve.localhost:verification-participant-dx)
#   --pdf               certificate document (default: the Verification UI's sample document)
#   -s, --short-name    the vendor participant (default: vendor-participant)
#
# Environment: VENDOR_DATASPACE, VENDOR_CLUSTER, VENDOR_HOST, VENDOR_PORT, VE_HOST (see lib.sh),
#              CREDENTIALS_TIMEOUT (seconds to wait for the verification participant's DID document,
#              the member credential and the inbox offer, default 900), TIMEOUT (seconds per negotiation/transfer/notify/listing wait,
#              default 180)

source "$(dirname "$0")/lib.sh"

VP_DID_ARG=""
PDF="$(cd "$(dirname "$0")/../.." && pwd)/verification-ui/src/main/resources/certificate-document.pdf"
CREDENTIALS_TIMEOUT="${CREDENTIALS_TIMEOUT:-900}"
TIMEOUT="${TIMEOUT:-180}"
MANAGEMENT_CONTEXT="https://w3id.org/edc/connector/management/v2"

usage() { awk '/^# Usage:/ { p = 1 } p && !/^#/ { exit } p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--dataspace|--vp-did|--pdf|-s|--short-name)
      [[ $# -ge 2 ]] || die "$1 requires a value"
      case "$1" in
        -d|--dataspace) use_dataspace "$2" ;;
        --vp-did) VP_DID_ARG="$2" ;;
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
case "$VENDOR_DATASPACE" in
  # a DECADE-X-style issuer id, as the VE's own DECADE-X sample certificate carries it
  decade-x) ISSUER_ID=DX-00000000 ;;
esac

init base64 uuidgen
[[ -r "$PDF" ]] || die "no certificate document at $PDF"
DID=$(participant_did)
PCID=$(participant_context_of "$DID") || exit 1
[[ -n "$PCID" ]] || die "no participant context for $DID — run create-participant.sh first"
log "vendor participant $DID (context $PCID)"

# ---- 1. the verification participant's DSP endpoint -------------------------------------------
# On a fresh VE the verification participant only exists once a run has ensured it.
VP_DID_URL=$(did_document_url "$VP_DID")
LAST_HINT=0
vp_resolvable() {
  ADVERTISED_DSP=$(curl -s -m 10 "$VP_DID_URL" \
    | jq -r '.service[]? | select(.type == "ProtocolEndpoint") | .serviceEndpoint' 2>/dev/null || true)
  [[ -n "$ADVERTISED_DSP" ]] && return 0
  if (( $(date +%s) - LAST_HINT >= 30 )); then
    LAST_HINT=$(date +%s)
    log "no ProtocolEndpoint in the DID document at $VP_DID_URL yet — has a run ensured the verification participant?"
  fi
  return 1
}
poll "$CREDENTIALS_TIMEOUT" 5 "the DID document of $VP_DID" vp_resolvable
VP_DSP=$(dsp_endpoint_for_profile "$ADVERTISED_DSP")
log "verification participant $VP_DID at $VP_DSP"

# ---- 2. the member credential -----------------------------------------------------------------
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

# ---- 3. issue ---------------------------------------------------------------------------------
# The certificate certifies the vendor participant's legal entity, under the member id its member
# credential carries — the one the VE requests the certificate for. DECADE-X assigns the DECADE-X-ID
# on approval, so the one the participant was provisioned with is only provisional.
HOLDER=$(member_credential_of "$PCID" \
  | jq -r '(.credentialSubject | if type == "array" then .[0] else . end).partner.partnerID // empty')
[[ -n "$HOLDER" ]] || die "the vendor participant's $MEMBER_CREDENTIAL carries no partner.partnerID"

certo POST "/participant-contexts/$PCID/documents" \
  "$(jq -n --arg content "$(base64 < "$PDF" | tr -d '\n')" '{mediaType: "application/pdf", contentBase64: $content}')"
expect_2xx "document upload"
DOCUMENT_ID=$(printf '%s' "$HTTP_BODY" | jq -r .documentId)
log "document $DOCUMENT_ID uploaded"

# SCE certificate types are lowercase codes; exactly one certified location is the MAIN_LOCATION.
certo POST "/participant-contexts/$PCID/certificates" "$(jq -n --arg holder "$HOLDER" --arg doc "$DOCUMENT_ID" \
    --arg issuer "$ISSUER_ID" --arg reg "vendor-$(date +%s)" '{
  certificateType: "iso9001",
  certificateTypeVersion: "2015",
  registrationNumber: $reg,
  validFrom: "2026-01-01",
  validUntil: "2030-01-01",
  certifiedLocations: [{legalEntityId: $holder, locationRole: "MAIN_LOCATION"}],
  issuer: {issuerName: "Vendor stack sample CA", issuerId: $issuer},
  documentIds: [$doc]
}')"
expect_2xx "certificate issuance"
CERTIFICATE_ID=$(printf '%s' "$HTTP_BODY" | jq -r .certificateId)
REVISION=$(printf '%s' "$HTTP_BODY" | jq -r .revision)
log "certificate $CERTIFICATE_ID revision $REVISION issued (iso9001, holder $HOLDER)"

# ---- 4. the inbox offer -----------------------------------------------------------------------
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
    matches=$(printf '%s' "$CATALOG" | sce_api_datasets "$SCE_CONSUMER_API")
    count=$(printf '%s' "$matches" | jq 'length')
    if (( count > 1 )); then
      die "the verification participant offers the SCE consumer API $count times ($(printf '%s' "$matches" | jq -r 'map(.["@id"]) | join(", ")')) — ambiguous"
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
      log "no SCE consumer API offer in the catalog yet (it offers: $(printf '%s' "$CATALOG" | jq -r '[.dataset | (if type == "array" then . elif . == null then [] else [.] end) | .[]["@id"]] | if length == 0 then "nothing" else join(", ") end')) — has the run ensured the verification participant?"
    else
      log "catalog request answered HTTP $HTTP_STATUS: $(printf '%s' "$HTTP_BODY" | head -c 300)"
    fi
  fi
  return 1
}
poll "$CREDENTIALS_TIMEOUT" 5 "the SCE consumer API offer in the catalog of $VP_DID" inbox_offer_visible
log "inbox offer found: dataset '$INBOX_ASSET', offer $(printf '%s' "$OFFER" | jq -r '.["@id"]')"

# ---- 5. the push flow -------------------------------------------------------------------------
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

# ---- 6. notify --------------------------------------------------------------------------------
# A lifecycle notification: Certo derives it (AVAILABLE, the latest revision) from the certificate's
# state. A retry MUST reuse the messageId — it becomes the event id the consumer deduplicates on.
NOTIFY=$(jq -n --arg id "$(uuidgen | tr '[:upper:]' '[:lower:]')" --arg protocol "$SCE_PROTOCOL" \
    --arg consumer "$VP_DID" --arg flow "$FLOW_ID" '{
  messageId: $id, protocol: $protocol, consumerId: $consumer, flowId: $flow
}')
notified() {
  certo POST "/participant-contexts/$PCID/certificates/$CERTIFICATE_ID/notify" "$NOTIFY"
  if [[ "$HTTP_STATUS" =~ ^4 ]]; then
    die "notify rejected with HTTP $HTTP_STATUS: $HTTP_BODY"
  fi
  if [[ "$HTTP_STATUS" =~ ^2 ]] && [[ "$(printf '%s' "$HTTP_BODY" | jq -r .consumerNotified)" == true ]]; then
    return 0
  fi
  log "notification not delivered yet (HTTP $HTTP_STATUS): $(printf '%s' "$HTTP_BODY" | head -c 300)"
  return 1
}
poll "$TIMEOUT" 5 "certificate $CERTIFICATE_ID's notification to reach the verification participant" notified
log "verification participant notified: $(printf '%s' "$HTTP_BODY" | jq -r '"\(.lifecycleStatus) revision \(.revision)"')"

# ---- 7. fulfil --------------------------------------------------------------------------------
# A request the VE made before step 3 waits (RECEIVED) for the certificate; one made since was
# fulfilled on arrival and is not listed. Only the verification participant's are fulfilled here:
# the push flow reaches it and no one else. The listing is retried only to ride out a backend that
# has not settled — no new waiting request can appear once the certificate is held.
fulfillable_listed() {
  certo GET "/participant-contexts/$PCID/certificates/$CERTIFICATE_ID/fulfillable-requests"
  [[ "$HTTP_STATUS" == 200 ]] && return 0
  log "listing the requests certificate $CERTIFICATE_ID covers answered HTTP $HTTP_STATUS: $(printf '%s' "$HTTP_BODY" | head -c 300)"
  return 1
}
poll "$TIMEOUT" 5 "the requests certificate $CERTIFICATE_ID covers" fulfillable_listed
REQUESTS="$HTTP_BODY"
printf '%s' "$REQUESTS" | jq -r --arg vp "$VP_DID" \
  '.items[] | select(.counterpartyId != $vp) | ">> skipping exchange \(.exchangeId) of \(.counterpartyId) — the push flow reaches only \($vp)"' >&2
FULFILLED=()
for exchange in $(printf '%s' "$REQUESTS" | jq -r --arg vp "$VP_DID" '.items[] | select(.counterpartyId == $vp) | .exchangeId'); do
  certo POST "/participant-contexts/$PCID/certificate-requests/$exchange/fulfill?flowId=$FLOW_ID"
  if [[ "$HTTP_STATUS" == 409 ]]; then
    # fulfilled or declined since the listing
    log "exchange $exchange no longer waits: $(printf '%s' "$HTTP_BODY" | head -c 300)"
    continue
  fi
  expect_2xx "fulfilling exchange $exchange"
  log "exchange $exchange $(printf '%s' "$HTTP_BODY" | jq -r .status)"
  FULFILLED+=("$exchange")
done

# A request of the verification participant still waiting is one this certificate does not cover
# (another type, or a location it does not certify) — the run would time out on it.
certo POST "/participant-contexts/$PCID/certificate-requests/query" '{}'
if [[ "$HTTP_STATUS" == 200 ]]; then
  printf '%s' "$HTTP_BODY" | jq -r --arg vp "$VP_DID" --arg cert "$CERTIFICATE_ID" \
    '.items[] | select(.counterpartyId == $vp)
     | ">> WARNING: exchange \(.exchangeId) of \($vp) still waits — certificate \($cert) does not cover \({certificateType, certifiedLocations} | tojson)"' >&2
fi

if (( ${#FULFILLED[@]} > 0 )); then
  EXCHANGES="${FULFILLED[*]} (fulfilled now)"
else
  EXCHANGES="none was waiting — the run's request is fulfilled on arrival; its exchange id is on the VE run (and status.sh)"
fi

cat <<EOF

Certificate issued and handed to the verification participant.
  certificate:  $CERTIFICATE_ID revision $REVISION (iso9001, holder $HOLDER)
  notified:     $VP_DID (AVAILABLE)
  exchange:     $EXCHANGES
  push flow:    $FLOW_ID (agreement $AGREEMENT_ID)

The verification run's REQUEST_CERTIFICATE step gets it FULFILLED.
EOF
