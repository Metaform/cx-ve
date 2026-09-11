package com.metaform.cxve.verification.domain.model;

/** One expected-event line of the verdict: how many events of {@code subject} were required vs found. */
public record ChecklistItem(String subject, int minCount, int actualCount, boolean satisfied) {
}
