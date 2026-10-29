package com.metaform.cxve.adapter.out.persistence.jpa;

import com.metaform.cxve.domain.model.OnboardingState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data queries backing {@link JpaOnboardingRepository}. The process row is authoritative
 * for the BPN and the holder DID (both seeded at submission), so the lookups are plain indexed
 * column comparisons — no fallback into the payload. {@code inactiveStates} is always
 * {@link JpaOnboardingRepository#INACTIVE_STATES} — passed in rather than inlined so the
 * definition lives in exactly one place.
 */
public interface SpringDataOnboardingProcessRepository extends JpaRepository<OnboardingProcessEntity, String> {

    @Query("""
            select e from OnboardingProcessEntity e
            where e.bpn = :bpn
              and e.state not in :inactiveStates
            """)
    List<OnboardingProcessEntity> findActiveByBpn(
            @Param("bpn") String bpn,
            @Param("inactiveStates") Collection<OnboardingState> inactiveStates);

    @Query("""
            select e from OnboardingProcessEntity e
            where e.holderId = :did
              and e.state not in :inactiveStates
            """)
    List<OnboardingProcessEntity> findActiveByDid(
            @Param("did") String did,
            @Param("inactiveStates") Collection<OnboardingState> inactiveStates);

    @Query("""
            select e from OnboardingProcessEntity e join e.uniqueIds u
            where u.idType = :idType
              and u.idValue = :idValue
              and e.state not in :inactiveStates
            """)
    List<OnboardingProcessEntity> findActiveByUniqueId(
            @Param("idType") String idType,
            @Param("idValue") String idValue,
            @Param("inactiveStates") Collection<OnboardingState> inactiveStates);

    /**
     * The client-scoped lookup behind the conflict check and the read/cancel endpoints — a list,
     * since (clientId, externalId) is not unique (legacy resubmissions, re-registration after a
     * cancellation).
     */
    List<OnboardingProcessEntity> findAllByClientIdAndExternalId(String clientId, String externalId);

    /** BEYOND-SPEC: every registration a client has submitted, any state (list endpoint). */
    List<OnboardingProcessEntity> findAllByClientId(String clientId);

    /**
     * BEYOND-SPEC: the atomic cancellation — one conditional UPDATE, so the still-non-terminal
     * check and the transition cannot be interleaved by a racing writer. Touches only state and
     * failureReason — identities assigned by the onboarding steps stay as recorded.
     *
     * @return the number of rows transitioned (0 when the process was already terminal or unknown)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OnboardingProcessEntity e
            set e.state = com.metaform.cxve.domain.model.OnboardingState.CANCELLED,
                e.failureReason = :reason
            where e.id = :id
              and e.state not in :terminalStates
            """)
    int cancel(@Param("id") String id,
               @Param("reason") String reason,
               @Param("terminalStates") Collection<OnboardingState> terminalStates);
}
