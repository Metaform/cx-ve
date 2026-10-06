package com.metaform.cxve.adapter.out.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A company unique id denormalized out of the payload so {@code findActiveByUniqueId} can match on
 * type + value in SQL. Component names carry the {@code id} prefix on purpose: both {@code value}
 * (a JPQL keyword) and bare {@code type} invite trouble as column/path names.
 */
@Embeddable
public record UniqueIdEmbeddable(
        @Column(name = "id_type") String idType,
        @Column(name = "id_value") String idValue) {
}
