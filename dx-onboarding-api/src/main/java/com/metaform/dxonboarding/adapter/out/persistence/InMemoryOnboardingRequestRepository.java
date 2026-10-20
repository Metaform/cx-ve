package com.metaform.dxonboarding.adapter.out.persistence;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.port.OnboardingRequestRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** In memory only: a restart forgets every request, which is fine for the VE. */
@Component
public class InMemoryOnboardingRequestRepository implements OnboardingRequestRepository {

    private final Map<String, OnboardingRequest> requests = new ConcurrentHashMap<>();
    private final AtomicLong businessNumbers = new AtomicLong();

    @Override
    public void save(OnboardingRequest request) {
        requests.put(request.id(), request);
    }

    @Override
    public void delete(String id) {
        requests.remove(id);
    }

    @Override
    public Optional<OnboardingRequest> findById(String id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public List<OnboardingRequest> findByConnector(String connectorId) {
        return find(request -> request.connectorId().equals(connectorId));
    }

    @Override
    public List<OnboardingRequest> findByPreferredDid(String did) {
        return find(request -> request.data().preferredDid().filter(did::equals).isPresent());
    }

    @Override
    public Optional<OnboardingRequest> findHolderOfLegalEntityId(String legalEntityId) {
        return requests.values().stream()
                .filter(OnboardingRequest::holdsLegalEntityId)
                .filter(request -> request.legalEntityId().equals(legalEntityId))
                .findFirst();
    }

    @Override
    public List<OnboardingRequest> findDraftsUpdatedBefore(Instant instant) {
        return find(request -> request.status() == OnboardingStatus.DRAFT && request.updatedAt().isBefore(instant));
    }

    @Override
    public long nextBusinessNumber() {
        return businessNumbers.incrementAndGet();
    }

    private List<OnboardingRequest> find(Predicate<OnboardingRequest> filter) {
        return requests.values().stream()
                .filter(filter)
                .sorted(Comparator.comparing(OnboardingRequest::createdAt))
                .toList();
    }
}
