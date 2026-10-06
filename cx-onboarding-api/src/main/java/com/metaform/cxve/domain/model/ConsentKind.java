package com.metaform.cxve.domain.model;

/**
 * What a {@link ConsentData consent} refers to (CX-0009 §3 ConsentKind). A tenant registration
 * MUST carry the three CX kinds; {@code OTHER} is an optional extra that must reference its
 * consent documents via {@code fileIds}.
 */
public enum ConsentKind {
    CX_OPERATING_MODEL,
    CX_TEN_GOLDEN_RULES,
    CX_DATA_EXCHANGE_GOVERNANCE,
    OTHER
}
