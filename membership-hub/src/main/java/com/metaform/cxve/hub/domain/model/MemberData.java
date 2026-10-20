package com.metaform.cxve.hub.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * The caller-facing membership request. It splits in two: the fields every dataspace shares, which
 * the hub itself acts on, and the {@code registration} object, whose shape is the dataspace's own
 * business — it is validated and translated into the dataspace's onboarding payload by that
 * dataspace's {@link com.metaform.cxve.hub.domain.port.DataspaceOnboarding} (for Catena-X: the
 * address, unique ids, company roles, agreements and initial users of a CX-0009 registration).
 *
 * <p>{@code memberId} is the member's identifier WITHIN the dataspace — the BPN in Catena-X, the
 * DECADE-X-ID in DECADE-X. Whether it is given up front is the dataspace's rule, checked by its
 * {@code DataspaceOnboarding}: Catena-X requires the BPN for every member; DECADE-X refuses one,
 * because the TSP assigns every DECADE-X-ID on approval, which the hub records. A member hosted
 * HERE needs its id before that, because provisioning reads it (the {@code cfm.issuer} VPA
 * properties feed it to the certo activity): for DECADE-X the hub generates one out of band
 * ({@code DataspaceOnboarding#memberIdForHostedMember}).
 *
 * <p>{@code did} decides whether this environment provisions anything. SUPPLY IT and the member
 * is taken to run elsewhere — its connector, wallet and DID document already exist, and the hub
 * only has the dataspace's onboarding offer it credentials. OMIT IT and the hub mints one under
 * this environment's authority (the {@code participant.did.template}) and provisions the member's
 * EDC resources here. The credential offer itself is the same either way; hosting is the only
 * difference, and it follows from who owns the identity.
 */
public record MemberData(
        @NotBlank String dataspace,
        @NotBlank String name,
        @NotBlank String shortName,
        String memberId,
        String did,
        Map<String, Object> registration
) {

    /** Whether this environment mints the member's identity, and with it provisions its resources. */
    @JsonIgnore
    public boolean hostedHere() {
        return did == null || did.isBlank();
    }

    /** The same request, with the member id the hub generated for it. */
    public MemberData withMemberId(String memberId) {
        return new MemberData(dataspace, name, shortName, memberId, did, registration);
    }

    /** The dataspace-specific part, never null — an absent object reads as empty. */
    @Override
    public Map<String, Object> registration() {
        return registration == null ? Map.of() : registration;
    }
}
