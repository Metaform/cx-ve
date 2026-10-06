package com.metaform.dxonboarding.adapter.out.auth;

/** Provides scoped tokens for this service's calls to platform APIs. */
public interface TokenProvider {

    /**
     * @param resource the jwtlet mapping to exchange under
     * @param scopes   the scopes the token must carry
     */
    String getToken(String resource, String scopes);
}
