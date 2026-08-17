package com.metaform.cxve.adapter.out.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.domain.model.CompanyUniqueIdData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.PartnerRegistration;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.OnboardingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed {@link OnboardingRepository} — the default: durable across restarts and shared
 * between replicas. The {@code test} profile swaps in the in-memory store instead (which carries
 * the complementary {@code @Profile("test")}, so exactly one of the two exists in any context).
 *
 * <p>The registration payload is stored as JSON and never queried through — the fields the
 * active-registration lookups need are denormalized onto the row at create time, which is safe
 * because the payload is immutable from then on ({@link #save} touches only the process fields).
 */
@Repository
@Profile("!test")
@Transactional
public class JpaOnboardingRepository implements OnboardingRepository {

    /**
     * The states {@link OnboardingProcess#isActiveRegistration()} excludes — the SQL queries take
     * the complement. Kept next to the queries as their parameter; parity with the domain predicate
     * is pinned by a test.
     */
    static final EnumSet<OnboardingState> INACTIVE_STATES =
            EnumSet.of(OnboardingState.SUBMITTED, OnboardingState.REJECTED, OnboardingState.FAILED);

    private final SpringDataOnboardingProcessRepository repository;
    // The payload is a self-contained JSON document; a plain mapper keeps its wire shape
    // independent of the web layer's JSON configuration (same reasoning as the NATS components).
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JpaOnboardingRepository(SpringDataOnboardingProcessRepository repository) {
        this.repository = repository;
    }

    @Override
    public void create(OnboardingProcess process, PartnerRegistrationData payload) {
        var entity = new OnboardingProcessEntity();
        applyProcess(process, entity);
        entity.setPayload(writePayload(payload));
        if (payload.uniqueIds() != null) {
            entity.setUniqueIds(payload.uniqueIds().stream()
                    .map(uid -> new UniqueIdEmbeddable(uid.type() == null ? null : uid.type().name(), uid.value()))
                    .toList());
        }
        repository.save(entity);
    }

    @Override
    public void save(OnboardingProcess process) {
        // Load-then-update, NOT a fresh entity: a fresh one would merge null over the payload
        // columns written at create.
        var entity = repository.findById(process.id()).orElseGet(OnboardingProcessEntity::new);
        applyProcess(process, entity);
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OnboardingProcess> findById(String processId) {
        return repository.findById(processId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OnboardingProcess> findByHolderId(String holderId) {
        // The holder id is seeded at submission, so a REJECTED duplicate of a running onboarding
        // carries the same one. Prefer the non-terminal match so issuance events keep flowing to
        // the onboarding that is still running; fall back to a terminal one so redelivered events
        // for a finished onboarding still resolve (a harmless no-op for the caller).
        var matches = repository.findByHolderId(holderId).stream().map(this::toDomain).toList();
        return matches.stream().filter(p -> !p.isTerminal()).findFirst()
                .or(() -> first(matches));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartnerRegistrationData> findPayload(String processId) {
        return repository.findById(processId)
                .map(OnboardingProcessEntity::getPayload)
                .map(this::readPayload);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartnerRegistration> findActiveByBpn(String bpn) {
        return toRegistration(repository.findActiveByBpn(bpn, INACTIVE_STATES));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartnerRegistration> findActiveByDid(String did) {
        return toRegistration(repository.findActiveByDid(did, INACTIVE_STATES));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartnerRegistration> findActiveByUniqueId(CompanyUniqueIdData uniqueId) {
        var type = uniqueId.type() == null ? null : uniqueId.type().name();
        return toRegistration(repository.findActiveByUniqueId(type, uniqueId.value(), INACTIVE_STATES));
    }

    private Optional<PartnerRegistration> toRegistration(List<OnboardingProcessEntity> matches) {
        // A process saved without a create has no payload; the in-memory store skips those in its
        // active queries, so this does too.
        return first(matches.stream().filter(e -> e.getPayload() != null).toList())
                .map(e -> PartnerRegistration.of(toDomain(e), readPayload(e.getPayload())));
    }

    private static <T> Optional<T> first(List<T> list) {
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private static void applyProcess(OnboardingProcess process, OnboardingProcessEntity entity) {
        entity.setId(process.id());
        entity.setExternalId(process.externalId());
        entity.setState(process.state());
        entity.setBpn(process.bpn());
        entity.setParticipantProfileId(process.participantProfileId());
        entity.setHolderId(process.holderId());
        entity.setFailureReason(process.failureReason());
        entity.setHolderProcessId(process.holderProcessId());
        entity.setTenantId(process.tenantId());
        entity.setParticipantContextId(process.participantContextId());
    }

    private OnboardingProcess toDomain(OnboardingProcessEntity entity) {
        return new OnboardingProcess(
                entity.getId(),
                entity.getExternalId(),
                entity.getState(),
                entity.getBpn(),
                entity.getParticipantProfileId(),
                entity.getHolderId(),
                entity.getFailureReason(),
                entity.getHolderProcessId(),
                entity.getTenantId(),
                entity.getParticipantContextId());
    }

    private String writePayload(PartnerRegistrationData payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Losing the payload would silently break the provisioning replay later; fail the
            // registration call instead.
            throw new IllegalStateException("Failed to serialize the registration payload", e);
        }
    }

    private PartnerRegistrationData readPayload(String json) {
        try {
            return objectMapper.readValue(json, PartnerRegistrationData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize a stored registration payload", e);
        }
    }
}
