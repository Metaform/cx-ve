package com.metaform.cxve.adapter.out.persistence.jpa;

import com.metaform.cxve.domain.model.OnboardingState;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<OnboardingProcessEntity> findByHolderId(String holderId);

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
}
