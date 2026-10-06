package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The status callback's counterpart for a dataspace whose onboarding API has none
 * ({@link DataspaceOnboarding#pollsStatus()}): every {@code participant.registration.poll-interval}
 * it reads the status of each submitted registration of such a dataspace and hands it to
 * {@link MembershipService#onRegistrationStatus(com.metaform.cxve.hub.domain.model.RegistrationOutcome)},
 * exactly as a callback would be — so the transition rules are the same for both.
 *
 * <p>A membership is polled while it awaits its outcome: in SUBMITTED (or the legacy REGISTERING)
 * with its onboarding process id recorded. A failed poll is logged and retried on the next run; it
 * never stops the others.
 */
@Component
public class RegistrationStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(RegistrationStatusPoller.class);

    private final MembershipRepository repository;
    private final DataspaceRegistry dataspaces;
    private final MembershipService membershipService;

    public RegistrationStatusPoller(MembershipRepository repository, DataspaceRegistry dataspaces,
                                    MembershipService membershipService) {
        this.repository = repository;
        this.dataspaces = dataspaces;
        this.membershipService = membershipService;
    }

    @Scheduled(fixedDelayString = "${participant.registration.poll-interval:15s}",
            initialDelayString = "${participant.registration.poll-interval:15s}")
    public void poll() {
        Stream.of(MembershipState.SUBMITTED, MembershipState.REGISTERING)
                .flatMap(state -> repository.findByState(state).stream())
                .filter(membership -> membership.onboardingProcessId() != null)
                .forEach(membership -> polledOnboarding(membership).ifPresent(onboarding -> poll(membership, onboarding)));
    }

    private void poll(Membership membership, DataspaceOnboarding onboarding) {
        try {
            membershipService.onRegistrationStatus(onboarding.pollStatus(membership));
        } catch (RuntimeException e) {
            log.warn("Membership '{}': polling the status of {} onboarding process '{}' failed — retrying next run: {}",
                    membership.externalId(), membership.dataspace(), membership.onboardingProcessId(), e.getMessage());
        }
    }

    /** The membership's onboarding, if its dataspace is served AND polled. */
    private Optional<DataspaceOnboarding> polledOnboarding(Membership membership) {
        try {
            return Optional.of(dataspaces.onboarding(membership.dataspace())).filter(DataspaceOnboarding::pollsStatus);
        } catch (DataspaceOnboarding.UnknownDataspaceException e) {
            return Optional.empty();
        }
    }
}
