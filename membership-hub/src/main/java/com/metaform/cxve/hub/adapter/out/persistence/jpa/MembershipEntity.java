package com.metaform.cxve.hub.adapter.out.persistence.jpa;

import com.metaform.cxve.hub.domain.model.MembershipState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping of a membership together with the request payload it was created from — one row per
 * membership. The row IS the externalId ↔ participantContextId correlation this app maintains;
 * the payload is stored as opaque JSON (never queried through) because provisioning replays the
 * agreements from it after the asynchronous CONFIRMED callback.
 */
@Entity
@Table(name = "membership")
public class MembershipEntity {

    /** The hub-minted external id — the key the Onboarding API's callbacks correlate on. */
    @Id
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "name")
    private String name;

    @Column(name = "did")
    private String did;

    @Column(name = "bpn")
    private String bpn;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private MembershipState state;

    @Column(name = "onboarding_process_id")
    private String onboardingProcessId;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "participant_profile_id")
    private String participantProfileId;

    @Column(name = "participant_context_id")
    private String participantContextId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "failure_reason")
    private String failureReason;

    /** The membership request as JSON ({@code text} on Postgres). Written once at create. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDid() {
        return did;
    }

    public void setDid(String did) {
        this.did = did;
    }

    public String getBpn() {
        return bpn;
    }

    public void setBpn(String bpn) {
        this.bpn = bpn;
    }

    public MembershipState getState() {
        return state;
    }

    public void setState(MembershipState state) {
        this.state = state;
    }

    public String getOnboardingProcessId() {
        return onboardingProcessId;
    }

    public void setOnboardingProcessId(String onboardingProcessId) {
        this.onboardingProcessId = onboardingProcessId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getParticipantProfileId() {
        return participantProfileId;
    }

    public void setParticipantProfileId(String participantProfileId) {
        this.participantProfileId = participantProfileId;
    }

    public String getParticipantContextId() {
        return participantContextId;
    }

    public void setParticipantContextId(String participantContextId) {
        this.participantContextId = participantContextId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
