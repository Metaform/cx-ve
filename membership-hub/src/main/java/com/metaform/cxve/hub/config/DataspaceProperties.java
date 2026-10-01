package com.metaform.cxve.hub.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The dataspaces this hub onboards members into, keyed by dataspace id ({@code dataspaces.<id>}).
 * Each entry carries what the hub needs to know about one dataspace: how to reach and
 * authenticate to its onboarding API, and the dataspace-dependent parts of a hosted member's
 * participant profile. Only {@code enabled} entries get a
 * {@link com.metaform.cxve.hub.domain.port.DataspaceOnboarding} bean — the others are known but
 * refused.
 *
 * <p>Bound by hand rather than through {@code @ConfigurationProperties}: the dataspace ids ARE the
 * top-level keys, which a properties class cannot express without an extra nesting level.
 */
public record DataspaceProperties(Map<String, Dataspace> dataspaces) {

    public DataspaceProperties {
        dataspaces = dataspaces == null ? Map.of() : Map.copyOf(dataspaces);
    }

    public static DataspaceProperties bind(Environment environment) {
        return new DataspaceProperties(Binder.get(environment)
                .bind("dataspaces", Bindable.mapOf(String.class, Dataspace.class))
                .orElse(Map.of()));
    }

    /** The settings of a configured dataspace, enabled or not. */
    public Optional<Dataspace> get(String id) {
        return Optional.ofNullable(dataspaces.get(id));
    }

    /**
     * @param enabled           whether members can be onboarded into this dataspace
     * @param displayName       the human-readable name, e.g. "Catena-X"
     * @param onboarding        the dataspace's onboarding API
     * @param dataspaceProfiles the connector's DSP dataspace profiles a hosted member is deployed with
     * @param memberIdClaim     where flow tokens take the caller's member id from
     */
    public record Dataspace(
            boolean enabled,
            String displayName,
            Onboarding onboarding,
            List<String> dataspaceProfiles,
            MemberIdClaim memberIdClaim
    ) {
    }

    /**
     * The onboarding API, called in the onboarding-service-provider role. {@code auth} is the hub's
     * own client-credentials identity towards it; {@code callback} is what the hub registers as its
     * status-callback address — the url must be reachable FROM the onboarding API, and the token
     * url and client are what it authenticates its callback calls with.
     */
    public record Onboarding(String url, Client auth, Callback callback) {
    }

    public record Client(String tokenUrl, String clientId, String clientSecret, String scope) {
    }

    public record Callback(String url, String tokenUrl, String clientId, String clientSecret) {
    }

    public record MemberIdClaim(String credentialType, String claim, String flowClaim) {
    }
}
