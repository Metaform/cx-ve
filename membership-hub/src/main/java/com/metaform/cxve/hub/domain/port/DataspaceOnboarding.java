package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import java.util.Map;

/**
 * One dataspace's onboarding, as the hub drives it. Everything dataspace-specific about getting a
 * member registered lives behind this port: the shape of the {@link MemberData#registration()}
 * object, the onboarding API's endpoints, payloads and authentication, and how it reports a
 * registration's outcome: through status callbacks in its own wire format (Catena-X), or as a
 * status the hub polls ({@link #pollsStatus()}, Decade-X). The hub's own choreography (deploy
 * first, register second, the reported outcome as the terminal signal) is the same for every
 * dataspace.
 *
 * <p>One implementation per dataspace, registered as a bean only while that dataspace is enabled
 * ({@code dataspaces.<id>.enabled}); {@link #dataspace()} is the id the member requests and the
 * callback path carry.
 */
public interface DataspaceOnboarding {

    /** The dataspace id, e.g. {@code catena-x} — the key under {@code dataspaces.*}. */
    String dataspace();

    /**
     * Checks the dataspace-specific {@code registration} object BEFORE anything is persisted or
     * deployed, so a bad request fails in the caller's call rather than on the worker.
     *
     * @throws InvalidRegistrationException naming every violation
     */
    void validate(MemberData data);

    /**
     * The {@code cfm.issuer} VPA properties the member's participant profile is deployed with —
     * what the platform's provisioning activities (certo's in particular) read the member's
     * dataspace identity from.
     */
    Map<String, Object> issuerProperties(String did, MemberData data);

    /**
     * Registers (or overwrites) this hub's status-callback address with the dataspace's onboarding
     * API. Called before every submission, so it must be idempotent; a no-op for a dataspace that
     * {@linkplain #pollsStatus() is polled}.
     */
    void registerCallback();

    /**
     * Submits the registration under the given external id and DID.
     *
     * @return the id of the onboarding process the dataspace's API created.
     */
    String submitRegistration(String externalId, String did, MemberData data);

    /** Translates a status callback in the dataspace's own wire format into the hub's outcome. */
    RegistrationOutcome readCallback(Map<String, Object> body);

    /**
     * Whether this dataspace's onboarding API reports a registration's outcome only when asked —
     * it has no status callbacks. The hub then polls {@link #pollStatus} for every submitted
     * membership of the dataspace, and refuses callbacks for it.
     */
    default boolean pollsStatus() {
        return false;
    }

    /**
     * Reads where the membership's registration stands from the onboarding API — the polling
     * counterpart of {@link #readCallback}, for a dataspace that {@link #pollsStatus()}.
     *
     * @param membership a submitted membership; its {@code onboardingProcessId} is set
     */
    default RegistrationOutcome pollStatus(Membership membership) {
        throw new UnsupportedOperationException(
                "The %s onboarding API reports status by callback, not polling".formatted(dataspace()));
    }

    /** Raised by {@link #validate} for a {@code registration} object the dataspace refuses. */
    class InvalidRegistrationException extends RuntimeException {

        public InvalidRegistrationException(String message) {
            super(message);
        }
    }

    /** A request named a dataspace this hub does not serve (unknown, or not enabled). */
    class UnknownDataspaceException extends RuntimeException {

        public UnknownDataspaceException(String dataspace) {
            super("Dataspace '%s' is not enabled on this hub".formatted(dataspace));
        }
    }
}
