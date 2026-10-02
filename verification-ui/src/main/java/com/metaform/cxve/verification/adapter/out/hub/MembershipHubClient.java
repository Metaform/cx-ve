package com.metaform.cxve.verification.adapter.out.hub;

import com.metaform.cxve.verification.adapter.out.http.HttpResult;
import com.metaform.cxve.verification.adapter.out.http.RestCalls;
import com.metaform.cxve.verification.application.Poller;
import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.Membership;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

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
     * Submits the member into the dataspace and returns the created membership record — the hub
     * mints the externalId and answers as soon as the first leg is under way: PROVISIONING for a
     * member it hosts (it deploys the resources before registering them), SUBMITTED for one that
     * brought its own DID. The rest lands asynchronously — poll {@link #awaitProvisioned}. A
     * membership already dead on arrival fails here, as does a DID or member id a live membership
     * already holds (409).
     *
     * <p>Declaring its own {@code externalDid} (nullable) is what tells the hub not to provision
     * anything for the participant — a membership without one gets a DID minted under this
     * environment's authority and its resources deployed here BEFORE it is registered. Either way
     * the registration ends with the issuer offering the participant its credentials.
     *
     * <p>The dataspace-specific {@code registration} object is the dataspace profile's template,
     * with the participant's identity filled in.
     */
    public Membership onboard(String dataspace, String name, String shortName, String memberId, String uniqueId,
                              String externalDid) {
        var body = mapper.createObjectNode();
        body.put("dataspace", dataspace);
        body.put("name", name);
        body.put("shortName", shortName);
        if (memberId != null) {
            body.put("memberId", memberId);
        }
        if (externalDid != null) {
            body.put("did", externalDid);
        }
        body.set("registration", registration(properties.dataspace(dataspace).registrationTemplate(), Map.of(
                "name", name, "shortName", shortName, "memberId", memberId == null ? "" : memberId,
                "uniqueId", uniqueId)));
        var response = RestCalls.post(hubRestClient, "/api/members", null, body.toString());
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

    /** The dataspace's memberships registered under a member id — the hub's rediscovery endpoint. */
    public List<Membership> findByMemberId(String dataspace, String memberId) {
        return findBy("memberId", memberId, dataspace);
    }

    /**
     * The dataspace's memberships registered under a DID. This is how a repeat run against the
     * same external participant finds what it already has: an onboarding API refuses to register a
     * DID that is already registered, so onboarding it again would be declined rather than repeated.
     */
    public List<Membership> findByDid(String dataspace, String did) {
        return findBy("did", did, dataspace);
    }

    /**
     * The ids of the dataspaces the hub onboards members into. Empty when the hub cannot be
     * reached — nothing can be verified then anyway.
     */
    public List<String> servedDataspaces() {
        HttpResult response;
        try {
            response = RestCalls.get(hubRestClient, "/api/dataspaces", null);
        } catch (ResourceAccessException e) {
            log.warn("hub unreachable while listing its dataspaces: {}", e.getMessage());
            return List.of();
        }
        if (!response.is2xx()) {
            log.warn("listing the hub's dataspaces failed with HTTP {}: {}", response.status(), response.body());
            return List.of();
        }
        try {
            var ids = new ArrayList<String>();
            mapper.readTree(response.body()).forEach(dataspace -> ids.add(dataspace.path("id").asText()));
            return List.copyOf(ids);
        } catch (JacksonException e) {
            throw new VerificationException("unparseable dataspace list from the hub: " + response.body(), e);
        }
    }

    private List<Membership> findBy(String filter, String value, String dataspace) {
        // The values go in as URI variables, NOT pre-encoded into the path: the client encodes
        // what it expands, so encoding them here too would escape a did:web's own '%' twice.
        var response = RestCalls.get(hubRestClient, "/api/members?dataspace={dataspace}&" + filter + "={value}",
                null, dataspace, value);
        if (response.status() != 200) {
            throw new VerificationException("membership lookup by %s %s in %s failed with HTTP %d: %s"
                    .formatted(filter, value, dataspace, response.status(), response.body()));
        }
        try {
            return mapper.readValue(response.body(), new TypeReference<List<Membership>>() { });
        } catch (JacksonException e) {
            throw new VerificationException("unparseable membership list from the hub: " + response.body(), e);
        }
    }

    /**
     * Polls the membership until its participant resources exist AND its registration is under way,
     * then returns the record. Both, because the hub deploys before it registers: the record passes
     * through PROVISIONED (participant context, no process id yet) on its way to SUBMITTED, and a
     * run needs the process id to read the member's event ledger. Waiting on the DATA rather than a
     * state name also means a record that raced ahead to CREDENTIALS_OFFERED between two polls is
     * accepted.
     *
     * <p>REJECTED, FAILED and REGISTERING fail immediately rather than burning the timeout.
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
                    if (!current.hasParticipantResources()) {
                        throw new Poller.RetryException("membership %s in state %s".formatted(externalId, current.state()));
                    }
                    return current;
                });
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

    /**
     * The registration template with the participant's identity filled in. Placeholders are
     * replaced inside the parsed JSON's text values, so a value can never break out of its string.
     */
    private JsonNode registration(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return fill(mapper.readTree(template), values);
        } catch (JacksonException e) {
            throw new VerificationException("the dataspace's registration template is not valid JSON: " + template, e);
        }
    }

    private static JsonNode fill(JsonNode node, Map<String, String> values) {
        if (node instanceof ObjectNode object) {
            object.propertyNames().forEach(name -> object.set(name, fill(object.get(name), values)));
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (var i = 0; i < array.size(); i++) {
                array.set(i, fill(array.get(i), values));
            }
            return array;
        }
        if (node.isString()) {
            var text = node.asString();
            for (var value : values.entrySet()) {
                text = text.replace("{{" + value.getKey() + "}}", value.getValue());
            }
            return StringNode.valueOf(text);
        }
        return node;
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
