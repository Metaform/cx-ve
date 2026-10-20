package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.adapter.in.dto.DocumentListView;
import com.metaform.dxonboarding.adapter.in.dto.DocumentResultView;
import com.metaform.dxonboarding.adapter.in.dto.OnboardingRequestView;
import com.metaform.dxonboarding.adapter.in.dto.VersionView;
import com.metaform.dxonboarding.application.OnboardingRequestService;
import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentUpload;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.UploadedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The TSP onboarding intake (DECADE-X {@code dataspace-api-docs.yaml}), served under
 * {@code /api/v1/onboarding-requests} — the specification's {@code /}: a draft is created from
 * JSON, its documents are added one by one, and it is submitted for review. Every response that
 * carries the request carries its version as {@code ETag}, and every change must send it back in
 * {@code If-Match}. Refusals are problems ({@link ProblemAdvice}).
 *
 * <p>It is meant to sit behind the dataspace's data plane, which relays a participant's calls
 * here and stamps the calling connector's identity on them; the caller cannot set it. Requests are
 * scoped to that identity.
 *
 * <p><b>ASSUMPTION — connector identity:</b> the specification leaves open how the calling
 * participant is identified to the TSP. This controller assumes a request header, named by
 * {@code dx-onboarding.connector-id-header} (default {@code X-Connector-Id}), and trusts it as is:
 * nothing verifies that the data plane set it rather than the caller. That is only sound while
 * this API is reachable through the data plane alone, and must be revisited once the real
 * mechanism is known. A warning is logged at startup, and for every call that arrives without the
 * header (refused as {@code unauthorized}).
 */
@RestController
@RequestMapping("/api/v1/onboarding-requests")
public class OnboardingRequestController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingRequestController.class);

    private static final String CONNECTOR_ID_HEADER = "${dx-onboarding.connector-id-header:X-Connector-Id}";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OnboardingRequestService service;
    private final String connectorIdHeader;

    public OnboardingRequestController(OnboardingRequestService service,
                                       @Value(CONNECTOR_ID_HEADER) String connectorIdHeader) {
        this.service = service;
        this.connectorIdHeader = connectorIdHeader;
        log.warn("Onboarding requests take the calling connector's identity from the '{}' header, UNVERIFIED — "
                + "an assumption about how the dataspace's data plane stamps it; this API must only be "
                + "reachable through that data plane", connectorIdHeader);
    }

    /** Creates a draft — or returns the one an earlier call with the same key or content created (200). */
    @PostMapping(path = {"", "/"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OnboardingRequestView> create(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody OnboardingRequestData data) {
        var caller = caller(connectorId, "create");
        warnAboutUnknownProperties(caller);
        var created = service.create(caller, data, idempotencyKey);
        return versioned(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED, created.request())
                .body(OnboardingRequestView.from(created.request()));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<OnboardingRequestView> get(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @PathVariable String requestId) {
        var request = service.get(caller(connectorId, "read"), requestId);
        return versioned(HttpStatus.OK, request).body(OnboardingRequestView.from(request));
    }

    /** Replaces the draft's data. */
    @PutMapping(path = "/{requestId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OnboardingRequestView> replace(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @RequestHeader(name = IfMatch.HEADER, required = false) String ifMatch,
            @PathVariable String requestId,
            @RequestBody OnboardingRequestData data) {
        var caller = caller(connectorId, "correct");
        warnAboutUnknownProperties(caller);
        var request = service.replace(caller, requestId, IfMatch.version(ifMatch), data);
        return versioned(HttpStatus.OK, request).body(OnboardingRequestView.from(request));
    }

    @DeleteMapping("/{requestId}")
    public ResponseEntity<Void> delete(@RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
                                       @RequestHeader(name = IfMatch.HEADER, required = false) String ifMatch,
                                       @PathVariable String requestId) {
        var caller = caller(connectorId, "delete");
        service.delete(caller, requestId, IfMatch.version(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{requestId}/documents")
    public ResponseEntity<DocumentListView> documents(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @PathVariable String requestId) {
        var request = service.get(caller(connectorId, "list the documents of"), requestId);
        return versioned(HttpStatus.OK, request).body(DocumentListView.from(request));
    }

    /** Adds a document: what it is in the form fields, the file in the {@code file} part. */
    @PostMapping(path = "/{requestId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResultView> addDocument(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @RequestHeader(name = IfMatch.HEADER, required = false) String ifMatch,
            @PathVariable String requestId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String useCaseId,
            @RequestParam(required = false) String label,
            @RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
        var caller = caller(connectorId, "add a document to");
        var version = IfMatch.version(ifMatch);
        var upload = DocumentUpload.of(type, useCaseId, label, uploaded(file));
        var change = service.addDocument(caller, requestId, version, upload);
        return versioned(HttpStatus.CREATED, change.request())
                .body(DocumentResultView.from(change.request(), change.document()));
    }

    /** Downloads a document's file, always as an attachment. */
    @GetMapping("/{requestId}/documents/{documentId}")
    public ResponseEntity<byte[]> download(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @PathVariable String requestId,
            @PathVariable String documentId) {
        var file = service.download(caller(connectorId, "download a document of"), requestId, documentId(documentId));
        var document = file.document();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(document.filename()))
                .body(file.content());
    }

    /** Replaces a document's file (and an {@code other}'s label); the document keeps its id. */
    @PutMapping(path = "/{requestId}/documents/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResultView> replaceDocument(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @RequestHeader(name = IfMatch.HEADER, required = false) String ifMatch,
            @PathVariable String requestId,
            @PathVariable String documentId,
            @RequestParam(required = false) String label,
            @RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
        var caller = caller(connectorId, "replace a document of");
        var change = service.replaceDocument(caller, requestId, documentId(documentId), IfMatch.version(ifMatch),
                label, uploaded(file));
        return versioned(HttpStatus.OK, change.request())
                .body(DocumentResultView.from(change.request(), change.document()));
    }

    @DeleteMapping("/{requestId}/documents/{documentId}")
    public ResponseEntity<VersionView> removeDocument(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @RequestHeader(name = IfMatch.HEADER, required = false) String ifMatch,
            @PathVariable String requestId,
            @PathVariable String documentId) {
        var caller = caller(connectorId, "remove a document of");
        var request = service.removeDocument(caller, requestId, documentId(documentId), IfMatch.version(ifMatch));
        return versioned(HttpStatus.OK, request).body(VersionView.from(request));
    }

    /** Submits the draft for review; a request already submitted is returned as it is. */
    @PostMapping("/{requestId}/submit")
    public ResponseEntity<OnboardingRequestView> submit(
            @RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
            @RequestHeader(name = IfMatch.HEADER, required = false) String ifMatch,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String requestId) {
        var caller = caller(connectorId, "submit");
        var request = service.submit(caller, requestId, IfMatch.version(ifMatch), idempotencyKey);
        return versioned(HttpStatus.OK, request).body(OnboardingRequestView.from(request));
    }

    /**
     * The stamped connector identity. Its absence means the call did not come through the data
     * plane, or the data plane stamps the identity some other way than assumed — either way worth
     * a warning, since it is the assumption this API rests on.
     */
    private String caller(String connectorId, String operation) {
        if (connectorId == null || connectorId.isBlank()) {
            log.warn("Onboarding request call refused ({}): no connector identity in the '{}' header — either the "
                    + "call bypassed the data plane, or the data plane does not stamp the identity as assumed",
                    operation, connectorIdHeader);
            throw new ProblemException(ProblemCode.UNAUTHORIZED,
                    "No identity of the calling participant: the '%s' header is missing".formatted(connectorIdHeader));
        }
        return connectorId;
    }

    /** The TSP ignores properties it does not know; this stand-in says so, as the caller may have meant them. */
    private void warnAboutUnknownProperties(String connectorId) {
        var unknown = UnknownProperties.ofCurrentRequest();
        if (!unknown.isEmpty()) {
            log.warn("onboarding request data of '{}': unknown properties ignored: {}", connectorId, unknown);
        }
    }

    private static ResponseEntity.BodyBuilder versioned(HttpStatus status, OnboardingRequest request) {
        return ResponseEntity.status(status).eTag(IfMatch.etag(request.version()));
    }

    private static UploadedFile uploaded(MultipartFile file) throws IOException {
        return file == null ? new UploadedFile(null, null, null)
                : new UploadedFile(file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    /** {@code attachment; filename="…"} — with the RFC 5987 form too, for a name that is not ASCII. */
    private static String attachment(String filename) {
        var disposition = ContentDisposition.attachment();
        var ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(filename);
        return (ascii ? disposition.filename(filename) : disposition.filename(filename, StandardCharsets.UTF_8))
                .build().toString();
    }

    /** A document id that is not even a UUID names no document. */
    private static UUID documentId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw ProblemException.notFound("No document " + documentId);
        }
    }
}
