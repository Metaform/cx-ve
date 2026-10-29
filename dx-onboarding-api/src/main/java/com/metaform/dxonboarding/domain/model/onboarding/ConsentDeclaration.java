package com.metaform.dxonboarding.domain.model.onboarding;

/**
 * The General Terms and Conditions the applicant signs: their signed copy is the request's
 * {@code gtc} document.
 *
 * @param versionNumber the version signed; changing it drops the signed copy on file
 */
public record ConsentDeclaration(String versionNumber) {
}
