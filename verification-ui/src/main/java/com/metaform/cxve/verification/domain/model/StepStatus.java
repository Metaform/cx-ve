package com.metaform.cxve.verification.domain.model;

public enum StepStatus {
    PENDING,
    RUNNING,
    OK,
    FAILED,
    /** Never reached because an earlier step failed. */
    SKIPPED
}
