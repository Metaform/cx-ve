package com.metaform.cxve.adapter.out.persistence;

import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistration;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.OnboardingRepository;
import java.util.List;
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
        // Terminal is terminal: a state recorded as an outcome must not be overwritten by a
        // racing writer holding a stale, non-terminal snapshot (e.g. the orchestrator's drive
        // saving a step result after a concurrent cancellation landed).
        processes.compute(process.id(), (id, stored) ->
                stored != null && stored.isTerminal() && stored.state() != process.state() ? stored : process);
    }

    @Override
    public Optional<OnboardingProcess> findById(String processId) {
        return Optional.ofNullable(processes.get(processId));
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

    @Override
    public List<OnboardingProcess> findAllByClientIdAndExternalId(String clientId, String externalId) {
        return processes.values().stream()
                .filter(p -> clientId.equals(p.clientId()) && externalId.equals(p.externalId()))
                .toList();
    }

    @Override
    public List<OnboardingProcess> findAllByClientId(String clientId) {
        return processes.values().stream()
                .filter(p -> clientId.equals(p.clientId()))
                .toList();
    }

    @Override
    public boolean cancel(String processId, String reason) {
        // BEYOND-SPEC (cancellation); compute() makes check-and-transition one atomic map operation.
        var transitioned = new boolean[1];
        processes.computeIfPresent(processId, (id, stored) -> {
            if (stored.isTerminal()) {
                return stored;
            }
            transitioned[0] = true;
            return stored.cancelled(reason);
        });
        return transitioned[0];
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
