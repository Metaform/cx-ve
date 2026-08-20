package com.metaform.cxve.adapter.out.persistence.jpa;

import com.metaform.cxve.domain.model.OnboardingState;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA mapping of an onboarding process together with its registration payload — one row per
 * onboarding, mirroring the two maps of the in-memory store.
 *
 * <p>The payload is stored as opaque JSON: no query decomposes it, and it must round-trip exactly
 * (it is replayed into the later onboarding steps). The process columns are authoritative for the
 * BPN and holder DID — seeded from the submission, so the lookups never fall back into the
 * payload. The one thing queries DO need from the payload, the unique ids, is denormalized into
 * the element collection below (matched on type + value).
 */
@Entity
@Table(name = "onboarding_process", indexes = {
        // Each queried identity has its column indexed: the holder id serves the active-DID
        // lookup, the BPN the active-BPN lookup.
        @Index(name = "idx_onboarding_process_holder", columnList = "holder_id"),
        @Index(name = "idx_onboarding_process_bpn", columnList = "bpn"),
        @Index(name = "idx_onboarding_process_state", columnList = "state")
})
public class OnboardingProcessEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "external_id")
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private OnboardingState state;

    @Column(name = "bpn")
    private String bpn;

    @Column(name = "holder_id")
    private String holderId;

    // Joined validation violations can exceed any fixed varchar guess.
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "failure_reason")
    private String failureReason;

    /** The authenticated OSP client that submitted the registration; callback-routing target. */
    @Column(name = "client_id")
    private String clientId;

    /** The registration payload as JSON ({@code text} on Postgres). Written once at create. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    // EAGER: the lists are a handful of entries, and it keeps the mapped domain object complete
    // regardless of where the entity ends up.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "onboarding_process_unique_id",
            joinColumns = @JoinColumn(name = "process_id"),
            // findActiveByUniqueId drives from this table: it matches type + value here, then
            // joins back to the process row.
            indexes = @Index(name = "idx_onboarding_unique_id_type_value", columnList = "id_type, id_value"))
    private List<UniqueIdEmbeddable> uniqueIds = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public OnboardingState getState() {
        return state;
    }

    public void setState(OnboardingState state) {
        this.state = state;
    }

    public String getBpn() {
        return bpn;
    }

    public void setBpn(String bpn) {
        this.bpn = bpn;
    }

    public String getHolderId() {
        return holderId;
    }

    public void setHolderId(String holderId) {
        this.holderId = holderId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public List<UniqueIdEmbeddable> getUniqueIds() {
        return uniqueIds;
    }

    public void setUniqueIds(List<UniqueIdEmbeddable> uniqueIds) {
        this.uniqueIds = uniqueIds;
    }
}
