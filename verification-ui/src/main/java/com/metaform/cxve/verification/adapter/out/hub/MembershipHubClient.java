package com.metaform.cxve.verification.adapter.out.hub;

import com.metaform.cxve.verification.adapter.out.http.HttpResult;
import com.metaform.cxve.verification.adapter.out.http.RestCalls;
import com.metaform.cxve.verification.application.Poller;
import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.Membership;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Client for the Membership Hub — onboarding and the eventlog read API. Ported from the e2e
 * suite's {@code MembershipHubApi}: same wire shapes (mirrored, no code dependency on the hub),
 * same fail-fast semantics on dead-end membership states. The hub's API is unauthenticated
 * (operator surface).
 */
@Component
public class MembershipHubClient {

    // The hub's GET refreshes non-terminal memberships against the Tenant Manager on every call
    // (real outbound traffic), so onboarding polls run at the e2e suite's calmer 5s cadence
    // rather than the UI poll interval.
    private static final Duration ONBOARDING_POLL_INTERVAL = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(MembershipHubClient.class);

    private final RestClient hubRestClient;
    private final ObjectMapper mapper;
    private final VerificationProperties properties;

    public MembershipHubClient(@Qualifier("hubRestClient") RestClient hubRestClient,
                               ObjectMapper mapper,
                               VerificationProperties properties) {
        this.hubRestClient = hubRestClient;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Submits the member and returns the created membership record — the hub mints the
     * externalId and answers as soon as the registration is submitted (typically in SUBMITTED);
     * confirmation and provisioning land asynchronously — poll {@link #awaitProvisioned}. A
     * membership already dead on arrival fails here.
     */
    public Membership onboard(String name, String shortName, String bpn, String vatId) {
        return onboard(name, shortName, bpn, vatId, null);
    }

    /**
     * As above, but for a participant whose resources already run elsewhere: declaring its own
     * {@code did} is what tells the hub not to provision anything for it — a membership without
     * one gets a DID minted under this environment's authority and its resources deployed here.
     * Either way the hub has the issuer offer the participant its credentials.
     */
    public Membership onboard(String name, String shortName, String bpn, String vatId, String externalDid) {
        var identity = externalDid == null ? "" : """
                ,
                  "did": "%s\"""".formatted(externalDid);
        var body = """
                {
                  "name": "%s",
                  "shortName": "%s",
                  "bpn": "%s",
                  "city": "Munich",
                  "streetName": "Otto-Hahn-Ring",
                  "countryAlpha2Code": "DE",
                  "region": "BY",
                  "uniqueIds": [ { "type": "VAT_ID", "value": "%s" } ],
                  "companyRoles": [ "ACTIVE_PARTICIPANT" ],
                  "agreements": [ { "agreementId": "Catena-X", "consentStatus": "ACTIVE" } ],
                  "userDetails": [ {
                    "providerId": "vui-user-%s",
                    "firstName": "Verification", "lastName": "Runner",
                    "email": "vui-%s@example.com"
                  } ]%s
                }""".formatted(name, shortName, bpn, vatId, vatId, vatId, identity);
        var response = RestCalls.post(hubRestClient, "/api/members", null, body);
        if (response.status() != 201) {
            throw new VerificationException("membership submission for '%s' failed with HTTP %d: %s"
                    .formatted(name, response.status(), response.body()));
        }
        var membership = parseMembership(response.body());
        failOnDeadEnd(membership);
        log.info("membership submitted: \"{}\" (externalId={}, state={})", name, membership.externalId(), membership.state());
        return membership;
    }

    public Membership get(String externalId) {
        var response = RestCalls.get(hubRestClient, "/api/members/" + externalId, null);
        if (response.status() != 200) {
            throw new VerificationException("membership lookup for %s failed with HTTP %d: %s"
                    .formatted(externalId, response.status(), response.body()));
        }
        return parseMembership(response.body());
    }

    /** The memberships registered under a BPN — the hub's rediscovery endpoint. */
    public List<Membership> findByBpn(String bpn) {
        return findBy("bpn", bpn);
    }

    /**
     * The memberships registered under a DID. This is how a repeat run against the same external
     * participant finds what it already has: the Onboarding API refuses to register a DID that is
     * already registered, so onboarding it again would be declined rather than repeated.
     */
    public List<Membership> findByDid(String did) {
        return findBy("did", did);
    }

    private List<Membership> findBy(String filter, String value) {
        // The value goes in as a URI variable, NOT pre-encoded into the path: the client encodes
        // what it expands, so encoding it here too would escape a did:web's own '%' twice.
        var response = RestCalls.get(hubRestClient, "/api/members?" + filter + "={value}", null, value);
        if (response.status() != 200) {
            throw new VerificationException("membership lookup by %s %s failed with HTTP %d: %s"
                    .formatted(filter, value, response.status(), response.body()));
        }
        try {
            return mapper.readValue(response.body(), new TypeReference<List<Membership>>() { });
        } catch (JacksonException e) {
            throw new VerificationException("unparseable membership list from the hub: " + response.body(), e);
        }
    }

    /**
     * Polls the membership until its participant resources exist and returns the record. REJECTED,
     * FAILED and REGISTERING fail immediately rather than burning the timeout; a provisioned record
     * without a participant context id fails too (wire-contract skew — a deployed hub older than
     * this app).
     *
     * <p>CREDENTIALS_OFFERED counts as provisioned: the hub offers every member its credentials
     * right after deploying it, so a record can pass through PROVISIONED between two polls.
     */
    public Membership awaitProvisioned(String externalId) {
        var membership = Poller.poll("membership %s to be provisioned".formatted(externalId),
                properties.timeouts().onboarding(), ONBOARDING_POLL_INTERVAL, () -> {
                    Membership current;
                    try {
                        current = get(externalId);
                    } catch (ResourceAccessException e) {
                        throw new Poller.RetryException("hub unreachable: " + e.getMessage(), e);
                    }
                    failOnDeadEnd(current);
                    if (!current.isProvisioned() && !current.isCredentialsOffered()) {
                        throw new Poller.RetryException("membership %s in state %s".formatted(externalId, current.state()));
                    }
                    return current;
                });
        if (membership.participantContextId() == null || membership.participantContextId().isBlank()) {
            throw new VerificationException(("provisioned membership %s carries no participantContextId — "
                    + "is the deployed hub older than this app?").formatted(externalId));
        }
        log.info("membership {} provisioned (pcid={}, did={})", externalId,
                membership.participantContextId(), membership.did());
        return membership;
    }

    /**
     * Polls a membership until the hub reports CREDENTIALS_OFFERED — the terminal success of every
     * member: the issuer has been asked to offer the membership credentials to the participant's
     * wallet, whether that wallet runs here or elsewhere. Dead ends fail immediately.
     */
    public Membership awaitCredentialsOffered(String externalId) {
        var membership = Poller.poll("membership %s to have its credentials offered".formatted(externalId),
                properties.timeouts().onboarding(), ONBOARDING_POLL_INTERVAL, () -> {
                    Membership current;
                    try {
                        current = get(externalId);
                    } catch (ResourceAccessException e) {
                        throw new Poller.RetryException("hub unreachable: " + e.getMessage(), e);
                    }
                    failOnDeadEnd(current);
                    if (!current.isCredentialsOffered()) {
                        throw new Poller.RetryException("membership %s in state %s".formatted(externalId, current.state()));
                    }
                    return current;
                });
        log.info("membership {} has been offered its credentials (did={})", externalId, membership.did());
        return membership;
    }

    /**
     * The participant's eventlog rollup from the hub's read API, keyed by the onboarding process
     * id. Empty while the tracker has not opened the participant yet (404); a 5xx is retryable —
     * callers poll.
     */
    public Optional<JsonNode> eventlog(String onboardingProcessId) {
        HttpResult response;
        try {
            response = RestCalls.get(hubRestClient, "/api/eventlog/participants/" + onboardingProcessId, null);
        } catch (ResourceAccessException e) {
            throw new Poller.RetryException("hub unreachable: " + e.getMessage(), e);
        }
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.is4xx()) {
            throw new VerificationException("eventlog lookup for %s failed with HTTP %d: %s"
                    .formatted(onboardingProcessId, response.status(), response.body()));
        }
        if (!response.is2xx()) {
            throw new Poller.RetryException("eventlog lookup for %s failed with HTTP %d (transient?)"
                    .formatted(onboardingProcessId, response.status()));
        }
        try {
            return Optional.of(mapper.readTree(response.body()));
        } catch (JacksonException e) {
            throw new VerificationException("unparseable eventlog rollup from the hub: " + response.body(), e);
        }
    }

    private Membership parseMembership(String body) {
        try {
            return mapper.readValue(body, Membership.class);
        } catch (JacksonException e) {
            throw new VerificationException("unparseable membership record from the hub: " + body, e);
        }
    }

    /** Terminal failures and never-provisioned registrations must not burn the poll budget. */
    private static void failOnDeadEnd(Membership membership) {
        if (membership.isDeadEnd()) {
            throw new VerificationException("membership %s ended as %s: %s".formatted(
                    membership.externalId(), membership.state(),
                    membership.failureReason() == null ? "no reason recorded" : membership.failureReason()));
        }
    }
}
