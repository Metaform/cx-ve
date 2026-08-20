package com.metaform.cxve.hub.adapter.out.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.port.MembershipRepository;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-backed {@link MembershipRepository} — the default: durable across restarts. The
 * {@code test} profile swaps in the in-memory store instead (complementary profile expressions,
 * so exactly one of the two exists in any context).
 */
@Repository
@Profile("!test")
@Transactional
public class JpaMembershipRepository implements MembershipRepository {

    private final SpringDataMembershipRepository repository;
    // The payload is a self-contained JSON document; a plain mapper keeps its wire shape
    // independent of the web layer's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JpaMembershipRepository(SpringDataMembershipRepository repository) {
        this.repository = repository;
    }

    @Override
    public void create(Membership membership, MemberData payload) {
        var entity = new MembershipEntity();
        updateEntity(entity, membership);
        entity.setPayload(writePayload(payload));
        repository.save(entity);
    }

    @Override
    public void save(Membership membership) {
        // Load-then-update, NOT a fresh entity: a fresh one would merge null over the payload
        // column written at create.
        var entity = repository.findById(membership.externalId()).orElseGet(MembershipEntity::new);
        updateEntity(entity, membership);
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Membership> findByExternalId(String externalId) {
        return repository.findById(externalId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberData> findPayload(String externalId) {
        return repository.findById(externalId)
                .map(MembershipEntity::getPayload)
                .map(this::readPayload);
    }

    private void updateEntity(MembershipEntity entity, Membership membership) {
        entity.setExternalId(membership.externalId());
        entity.setName(membership.name());
        entity.setDid(membership.did());
        entity.setBpn(membership.bpn());
        entity.setState(membership.state());
        entity.setOnboardingProcessId(membership.onboardingProcessId());
        entity.setTenantId(membership.tenantId());
        entity.setParticipantProfileId(membership.participantProfileId());
        entity.setParticipantContextId(membership.participantContextId());
        entity.setFailureReason(membership.failureReason());
    }

    private Membership toDomain(MembershipEntity entity) {
        return new Membership(
                entity.getExternalId(),
                entity.getName(),
                entity.getDid(),
                entity.getBpn(),
                entity.getState(),
                entity.getOnboardingProcessId(),
                entity.getTenantId(),
                entity.getParticipantProfileId(),
                entity.getParticipantContextId(),
                entity.getFailureReason());
    }

    private String writePayload(MemberData payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Losing the payload would silently break the provisioning replay later; fail the
            // membership call instead.
            throw new IllegalStateException("Failed to serialize the membership payload", e);
        }
    }

    private MemberData readPayload(String json) {
        try {
            return objectMapper.readValue(json, MemberData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize a stored membership payload", e);
        }
    }
}
