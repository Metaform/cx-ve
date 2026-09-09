package com.metaform.cxve.application;

import com.metaform.cxve.domain.CancellationNotAllowedException;
import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import java.util.List;
import java.util.NoSuchElementException;

public interface NetworkService {

    /**
     * Registers a partner company on behalf of the given authenticated client, which becomes the
     * routing target for the registration's status callbacks.
     *
     * @return the id of the created onboarding process
     */
    String registerPartner(String clientId, PartnerRegistrationData registrationData);

    /**
     * Registers a tenant invited by the given OSP client (CX-0009 §2.2.2) — the fully
     * OSP-mediated flow, running the same onboarding as {@link #registerPartner}.
     *
     * @return the id of the created onboarding process
     * @throws DuplicateRegistrationException when this client already submitted a registration
     *         under the payload's externalId (answered with 409 at the web boundary)
     */
    String registerTenant(String clientId, OspTenantRegistrationData tenantData);

    /**
     * The registration the given client submitted under this externalId — the recovery path for
     * a lost status callback (a cx-ve extension; the spec declares no read endpoint). Scoped to
     * the caller: a foreign externalId is indistinguishable from an unknown one.
     *
     * @throws NoSuchElementException when this client has no such registration (answered with 404)
     */
    OnboardingProcess getRegistration(String clientId, String externalId);

    /** Every registration the given client has submitted, any state. */
    List<OnboardingProcess> listRegistrations(String clientId);

    /**
     * Cancels an in-flight registration of the given client — a terminal off-ramp initiated by
     * the OSP itself, so no status callback is sent (the DELETE response is the acknowledgment);
     * subscribers still receive the terminal {@code OnboardingCompleted} event.
     *
     * @return the process in its cancelled state
     * @throws NoSuchElementException when this client has no such registration (404)
     * @throws CancellationNotAllowedException when the registration is already terminal (409)
     */
    OnboardingProcess cancelRegistration(String clientId, String externalId);
}
