package com.metaform.dxonboarding.domain.model.onboarding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A use case agreement (UCA) the applicant signs: its signed copy is the request's {@code uca}
 * document for {@code useCaseId}. The applicant's {@code useCaseName} is deprecated and ignored —
 * the TSP names the use case ({@link UseCase#displayName()}).
 *
 * @param versionNumber the version signed; changing it drops the signed copy on file
 */
@JsonIgnoreProperties("useCaseName")
public record UseCaseAgreement(UseCase useCaseId, String versionNumber) {
}
