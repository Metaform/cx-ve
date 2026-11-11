package com.metaform.dxonboarding.domain.model.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * What the review asks the applicant to change before it carries on: the request is
 * {@code CHANGES_REQUESTED} until the applicant submits it again.
 *
 * @param requestedAt   when the review asked
 * @param comment       what it asks, in the reviewer's words
 * @param items         the fields and documents it concerns; empty when the comment says it all
 * @param resubmittedAt when the applicant submitted the request again; null until then
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeRequest(Instant requestedAt, String comment, List<Item> items, Instant resubmittedAt) {

    public ChangeRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Changes asked for at the given instant, not answered yet. */
    public static ChangeRequest of(Instant requestedAt, String comment, List<Item> items) {
        return new ChangeRequest(requestedAt, comment, items, null);
    }

    /** Answered: the applicant submitted the request again. */
    public ChangeRequest resubmitted(Instant at) {
        return new ChangeRequest(requestedAt, comment, items, at);
    }

    /**
     * One thing to change ({@code ChangeRequestItemDto}): a field of the request's data, or one of
     * its documents.
     *
     * @param field        the field concerned, e.g. {@code legalEntity.legalName}; null for a document
     * @param documentType the kind of document concerned; null for a field
     * @param note         what is wrong with it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String field, DocumentType documentType, String note) {
    }
}
