package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding.UnknownDataspaceException;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The dataspaces this hub serves: each enabled dataspace's {@link DataspaceOnboarding} together
 * with its configured settings. A dataspace without an onboarding bean is not served, whatever
 * its configuration says.
 */
@Component
public class DataspaceRegistry {

    private final Map<String, DataspaceOnboarding> onboardings;
    private final DataspaceProperties properties;

    public DataspaceRegistry(List<DataspaceOnboarding> onboardings, DataspaceProperties properties) {
        this.onboardings = onboardings.stream()
                .collect(Collectors.toUnmodifiableMap(DataspaceOnboarding::dataspace, Function.identity()));
        this.properties = properties;
    }

    /** @throws UnknownDataspaceException for a dataspace that is unknown or not enabled */
    public DataspaceOnboarding onboarding(String dataspace) {
        var onboarding = onboardings.get(dataspace);
        if (onboarding == null) {
            throw new UnknownDataspaceException(dataspace);
        }
        return onboarding;
    }

    /** The parts of a hosted member's participant profile that depend on its dataspace. */
    public TenantManager.DeploymentSpec deploymentSpec(String dataspace, Map<String, Object> issuerProperties) {
        var settings = properties.get(dataspace).orElseThrow(() -> new UnknownDataspaceException(dataspace));
        var claim = settings.memberIdClaim();
        return new TenantManager.DeploymentSpec(
                issuerProperties,
                settings.dataspaceProfiles() == null ? List.of() : settings.dataspaceProfiles(),
                claim == null ? null : new TenantManager.MemberIdClaim(claim.credentialType(), claim.claim(), claim.flowClaim()));
    }

    /** The dataspaces members can be onboarded into, ordered by id. */
    public List<ServedDataspace> served() {
        return onboardings.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .map(id -> new ServedDataspace(id, properties.get(id)
                        .map(DataspaceProperties.Dataspace::displayName)
                        .orElse(id)))
                .toList();
    }

    public record ServedDataspace(String id, String displayName) {
    }
}
