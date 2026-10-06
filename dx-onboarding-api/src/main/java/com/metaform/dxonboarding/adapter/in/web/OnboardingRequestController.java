package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.adapter.in.dto.ErrorList;
import com.metaform.dxonboarding.adapter.in.dto.OnboardingRequestReceipt;
import com.metaform.dxonboarding.adapter.in.dto.OnboardingRequestView;
import com.metaform.dxonboarding.application.OnboardingRequestService;
import com.metaform.dxonboarding.domain.InvalidOnboardingRequestException;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import com.metaform.dxonboarding.domain.model.onboarding.SubmittedDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * The TSP onboarding intake (DECADE-X {@code dataspace-api-docs}): submit an onboarding request
 * as a multipart body, and read it back by its id. It is meant to sit behind the federated
 * connector's data plane, which forwards a participant's calls here and stamps the calling
 * connector's identity on them; the caller cannot set it. Requests are scoped to that identity.
 *
 * <p><b>ASSUMPTION — connector identity:</b> the specification says the data plane adds the
 * calling connector's identity "from the contract agreement", but not HOW it reaches the TSP. This
 * controller assumes a request header, named by {@code dx-onboarding.connector-id-header}
 * (default {@code X-Connector-Id}), and trusts it as is: nothing verifies that the data plane set
 * it rather than the caller. That is only sound while this API is reachable through the data plane
 * alone, and must be revisited once the real mechanism is known. A warning is logged at startup,
 * and for every call that arrives without the header (refused with 400).
 */
@RestController
@RequestMapping("/api/v1/onboarding-requests")
public class OnboardingRequestController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingRequestController.class);

    private static final String CONNECTOR_ID_HEADER = "${dx-onboarding.connector-id-header:X-Connector-Id}";
    private static final String REQUEST_PART = "request";

    private final OnboardingRequestService service;
    private final String connectorIdHeader;

    public OnboardingRequestController(OnboardingRequestService service,
                                       @Value(CONNECTOR_ID_HEADER) String connectorIdHeader) {
        this.service = service;
        this.connectorIdHeader = connectorIdHeader;
        log.warn("Onboarding requests take the calling connector's identity from the '{}' header, UNVERIFIED — "
                + "an assumption about how the federated connector's data plane stamps it; this API must only be "
                + "reachable through that data plane", connectorIdHeader);
    }

    /**
     * Creates and submits a request in one step. The {@code request} part is the JSON request;
     * every other file part is a document, told apart by its part name.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OnboardingRequestReceipt submit(@RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
                                           @RequestPart(name = REQUEST_PART, required = false) OnboardingRequestData request,
                                           @RequestParam MultiValueMap<String, MultipartFile> parts) throws IOException {
        var documents = new ArrayList<SubmittedDocument>();
        for (var part : parts.entrySet()) {
            if (part.getKey().equals(REQUEST_PART)) {
                continue;
            }
            for (var file : part.getValue()) {
                documents.add(new SubmittedDocument(part.getKey(), file.getOriginalFilename(), file.getContentType(),
                        file.getBytes()));
            }
        }
        var caller = requireConnectorId(connectorId, "submit");
        return OnboardingRequestReceipt.from(service.submit(caller, new Submission(request, documents)));
    }

    @GetMapping("/{requestId}")
    public OnboardingRequestView get(@RequestHeader(name = CONNECTOR_ID_HEADER, required = false) String connectorId,
                                     @PathVariable String requestId) {
        return OnboardingRequestView.from(service.get(requireConnectorId(connectorId, "read"), requestId));
    }

    /**
     * The stamped connector identity. Its absence means the call did not come through the data
     * plane, or the data plane stamps the identity some other way than assumed — either way worth
     * a warning, since it is the assumption this API rests on.
     */
    private String requireConnectorId(String connectorId, String operation) {
        if (connectorId == null || connectorId.isBlank()) {
            log.warn("Onboarding request {} refused: no connector identity in the '{}' header — either the call "
                    + "bypassed the data plane, or the data plane does not stamp the identity as assumed",
                    operation, connectorIdHeader);
            throw new MissingConnectorIdentityException(connectorIdHeader);
        }
        return connectorId;
    }

    @ExceptionHandler(InvalidOnboardingRequestException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ErrorList invalid(InvalidOnboardingRequestException e) {
        return new ErrorList(e.violations());
    }

    @ExceptionHandler(MissingConnectorIdentityException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorList missingConnectorId(MissingConnectorIdentityException e) {
        return ErrorList.of(e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorList notFound(NoSuchElementException e) {
        return ErrorList.of(e.getMessage());
    }

    // reaches this handler only because multipart requests are resolved lazily (application.yaml):
    // eagerly, the limit is hit before a handler is chosen
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    public ErrorList tooLarge(MaxUploadSizeExceededException e) {
        return ErrorList.of("Documents may be up to 10 MB each and 25 MB per request");
    }

    static class MissingConnectorIdentityException extends RuntimeException {

        MissingConnectorIdentityException(String header) {
            super("No connector identity: the '%s' header is missing".formatted(header));
        }
    }
}
