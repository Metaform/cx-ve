package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.config.VerificationProperties.DataspaceProfile;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * What a run can be started for: every configured dataspace with its use cases, and whether each
 * is actually verifiable right now. A dataspace is available when the Membership Hub onboards
 * members into it; a use case when it is enabled in the dataspace's profile AND this environment
 * implements its verification. Unavailable entries are still listed — the UI shows them disabled,
 * so an operator sees what exists and why it cannot be picked.
 */
@Service
public class DataspaceCatalog {

    private final VerificationProperties properties;
    private final MembershipHubClient hub;
    private final Map<String, UseCaseFlow> flows;

    public DataspaceCatalog(VerificationProperties properties, MembershipHubClient hub, List<UseCaseFlow> flows) {
        this.properties = properties;
        this.hub = hub;
        this.flows = flows.stream().collect(Collectors.toUnmodifiableMap(UseCaseFlow::useCase, Function.identity()));
    }

    /** The catalog, in configuration order. Asks the hub which dataspaces it serves on every call. */
    public List<Dataspace> list() {
        var served = hub.servedDataspaces();
        return properties.dataspaces().entrySet().stream()
                .map(entry -> dataspace(entry.getKey(), entry.getValue(), served.contains(entry.getKey())))
                .toList();
    }

    /**
     * Checks a run request against the catalog and returns the flow that verifies it.
     *
     * @throws InvalidRunRequestException for a dataspace or use case that is unknown or unavailable,
     *                                    or a member id the dataspace's format does not allow
     */
    public UseCaseFlow resolve(String dataspace, String useCase, String memberId) {
        var profile = properties.dataspaces().get(dataspace);
        if (profile == null) {
            throw new InvalidRunRequestException("Unknown dataspace '%s'".formatted(dataspace));
        }
        if (!hub.servedDataspaces().contains(dataspace)) {
            throw new InvalidRunRequestException(
                    "%s is not available: the Membership Hub does not onboard members into it".formatted(profile.displayName()));
        }
        var configured = profile.useCases().get(useCase);
        if (configured == null) {
            throw new InvalidRunRequestException("%s has no use case '%s'".formatted(profile.displayName(), useCase));
        }
        var flow = flows.get(useCase);
        if (!configured.enabled() || flow == null) {
            throw new InvalidRunRequestException("%s in %s cannot be verified yet"
                    .formatted(configured.displayName(), profile.displayName()));
        }
        var format = profile.memberId();
        if (format != null && format.pattern() != null && !format.pattern().isBlank()
                && !Pattern.matches(format.pattern(), memberId)) {
            throw new InvalidRunRequestException("'%s' is not a valid %s %s (expected e.g. %s)"
                    .formatted(memberId, profile.displayName(), format.label(), format.example()));
        }
        return flow;
    }

    private Dataspace dataspace(String id, DataspaceProfile profile, boolean served) {
        var useCases = profile.useCases().entrySet().stream()
                .map(entry -> new UseCase(entry.getKey(), entry.getValue().displayName(),
                        served && entry.getValue().enabled() && flows.containsKey(entry.getKey())))
                .toList();
        return new Dataspace(id, profile.displayName(), served, profile.memberId(), useCases);
    }

    /** A dataspace as the UI offers it; {@code available} is false when the hub does not serve it. */
    public record Dataspace(String id, String displayName, boolean available,
                            VerificationProperties.MemberId memberId, List<UseCase> useCases) {
    }

    public record UseCase(String id, String displayName, boolean available) {
    }

    /** A run request the catalog refuses — the caller's input, not this environment, is wrong. */
    public static class InvalidRunRequestException extends RuntimeException {

        public InvalidRunRequestException(String message) {
            super(message);
        }
    }
}
