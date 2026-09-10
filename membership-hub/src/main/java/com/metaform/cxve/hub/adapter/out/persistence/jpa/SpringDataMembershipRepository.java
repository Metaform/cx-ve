package com.metaform.cxve.hub.adapter.out.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data backing for {@link JpaMembershipRepository}; the external id is the primary key. */
public interface SpringDataMembershipRepository extends JpaRepository<MembershipEntity, String> {

    List<MembershipEntity> findByBpn(String bpn);
}
