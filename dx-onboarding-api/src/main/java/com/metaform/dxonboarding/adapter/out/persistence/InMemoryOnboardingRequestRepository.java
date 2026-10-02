package com.metaform.dxonboarding.adapter.out.persistence;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In memory only: a restart forgets every request, which is fine for the VE. */
@Component
public class InMemoryOnboardingRequestRepository implements OnboardingRequestRepository {

    private final Map<String, OnboardingRequest> requests = new ConcurrentHashMap<>();

    @Override
    public void save(OnboardingRequest request) {
        requests.put(request.id(), request);
    }

    @Override
    public Optional<OnboardingRequest> findById(String id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public Optional<OnboardingRequest> findByFingerprint(String connectorId, Submission.Fingerprint fingerprint) {
        return requests.values().stream()
                .filter(request -> request.connectorId().equals(connectorId))
                .filter(request -> request.fingerprint().equals(fingerprint))
                .findFirst();
    }

    @Override
    public long count() {
        return requests.size();
    }
}
