package com.beardyinc.cxve.infrastructure.persistence;

import com.beardyinc.cxve.domain.model.OnboardingProcess;
import com.beardyinc.cxve.domain.model.PartnerRegistrationData;
import com.beardyinc.cxve.domain.port.OnboardingRepository;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link OnboardingRepository}. State is lost on restart and not shared across replicas —
 * fine for local/stub use; replace with a persistent implementation for production.
 */
@Repository
public class InMemoryOnboardingRepository implements OnboardingRepository {

    private final ConcurrentHashMap<String, OnboardingProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PartnerRegistrationData> payloads = new ConcurrentHashMap<>();
    // Secondary index holderId -> processId, so issuance events arriving over NATS can be correlated
    // back to a process. Maintained on every save from OnboardingProcess.holderId().
    private final ConcurrentHashMap<String, String> holderIndex = new ConcurrentHashMap<>();

    @Override
    public void create(OnboardingProcess process, PartnerRegistrationData payload) {
        payloads.put(process.id(), payload);
        save(process);
    }

    @Override
    public void save(OnboardingProcess process) {
        processes.put(process.id(), process);
        if (process.holderId() != null) {
            holderIndex.put(process.holderId(), process.id());
        }
    }

    @Override
    public Optional<OnboardingProcess> findById(String processId) {
        return Optional.ofNullable(processes.get(processId));
    }

    @Override
    public Optional<OnboardingProcess> findByHolderId(String holderId) {
        return Optional.ofNullable(holderIndex.get(holderId)).map(processes::get);
    }

    @Override
    public Optional<PartnerRegistrationData> findPayload(String processId) {
        return Optional.ofNullable(payloads.get(processId));
    }
}
