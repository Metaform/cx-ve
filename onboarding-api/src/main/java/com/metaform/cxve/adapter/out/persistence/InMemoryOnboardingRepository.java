package com.metaform.cxve.adapter.out.persistence;

import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistration;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.OnboardingRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link OnboardingRepository}, active only under the {@code test} profile. State is
 * lost on restart and not shared across replicas; everywhere else the durable
 * {@code JpaOnboardingRepository} is the default (complementary profile expressions, so exactly
 * one of the two exists in any context).
 */
@Repository
@Profile("test")
public class InMemoryOnboardingRepository implements OnboardingRepository {

    private final ConcurrentHashMap<String, OnboardingProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PartnerRegistrationData> payloads = new ConcurrentHashMap<>();

    @Override
    public void create(OnboardingProcess process, PartnerRegistrationData payload) {
        payloads.put(process.id(), payload);
        save(process);
    }

    @Override
    public void save(OnboardingProcess process) {
        processes.put(process.id(), process);
    }

    @Override
    public Optional<OnboardingProcess> findById(String processId) {
        return Optional.ofNullable(processes.get(processId));
    }

    @Override
    public Optional<OnboardingProcess> findByHolderId(String holderId) {
        // A scan, not an index: the holder id is seeded at submission, so a REJECTED duplicate of a
        // running onboarding carries the same one — an index keyed on it would let whichever saved
        // last shadow the other. Preferring the non-terminal match keeps issuance events flowing to
        // the onboarding that is still running; the terminal fallback keeps redelivered events for
        // a finished one resolvable (a harmless no-op for the caller).
        var matches = processes.values().stream()
                .filter(p -> holderId.equals(p.holderId()))
                .toList();
        return matches.stream().filter(p -> !p.isTerminal()).findFirst()
                .or(() -> matches.stream().findFirst());
    }

    @Override
    public Optional<PartnerRegistrationData> findPayload(String processId) {
        return Optional.ofNullable(payloads.get(processId));
    }

    @Override
    public Optional<PartnerRegistration> findActiveByBpn(String bpn) {
        return findActive(r -> bpn.equals(r.data().bpn()));
    }

    @Override
    public Optional<PartnerRegistration> findActiveByDid(String did) {
        return findActive(r -> did.equals(r.data().did()));
    }

    @Override
    public Optional<PartnerRegistration> findActiveByUniqueId(CompanyUniqueIdData uniqueId) {
        return findActive(r -> r.data().uniqueIds() != null && r.data().uniqueIds().contains(uniqueId));
    }

    private Optional<PartnerRegistration> findActive(Predicate<PartnerRegistration> predicate) {
        return processes.values().stream()
                .filter(OnboardingProcess::isActiveRegistration)
                .map(process -> {
                    var payload = payloads.get(process.id());
                    return payload == null ? null : PartnerRegistration.of(process, payload);
                })
                .filter(Objects::nonNull)
                .filter(predicate)
                .findFirst();
    }
}
