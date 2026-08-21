package com.metaform.cxve.adapter.out.callback;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaform.cxve.domain.model.CallbackRequestData;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OspRegistrationCallbackData;
import com.metaform.cxve.domain.model.RegistrationStatus;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Holds one callback registration per onboarding service provider, keyed on the client identity
 * it registered under — so a second provider setting its callback no longer overwrites the first.
 * The key is the caller's authenticated identity, handed in by the controller; it is never taken
 * from the payload. Status updates are routed to the callback of the client that submitted the
 * process ({@link OnboardingProcess#clientId()}); a process without a recorded submitter is
 * dropped with a warning — no provider gets to see a registration that is not its own.
 *
 * <p>A registration carrying an {@code authUrl} opts its callback into authentication: the status
 * update is sent with a bearer token obtained via OAuth2 client_credentials from that URL, using
 * the client id/secret the provider registered alongside it. A failed token fetch DROPS the
 * update (logged) rather than falling back to an unauthenticated call — the provider asked for
 * auth, so an anonymous POST must never arrive. Without an authUrl the callback is called plain,
 * as before.
 *
 * <p>Storage lives behind {@link CallbackStore}: Vault by default (the registration carries the
 * provider's OAuth2 client secret), in-memory under the "test" profile.
 */
@Service
public class DefaultRegistrationStatusService implements RegistrationStatusService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRegistrationStatusService.class);

    private final CallbackStore callbacks;

    public DefaultRegistrationStatusService(CallbackStore callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public CallbackRequestData getCallbackAddress(String clientId) {
        return callbacks.get(clientId);
    }

    @Override
    public void setCallbackAddress(String clientId, CallbackRequestData callbackData) {
        log.debug("Setting callback address for client ID '{}' to URL '{}'", clientId, callbackData.callbackUrl());
        callbacks.put(clientId, callbackData);
    }

    @Override
    public void invokeCallback(OnboardingProcess after) {
        if (after.clientId() == null) {
            log.warn("No client-ID registered for onboarding {}, dropping the status update", after.id());
            return;
        }
        // The process knows its submitter: only that provider's callback is notified — other
        // providers have no business seeing this registration's outcome. The store read shares
        // the fire-and-forget contract of the call itself: an unreachable store must not fail
        // the onboarding whose outcome is being announced.
        CallbackRequestData callback;
        try {
            callback = callbacks.get(after.clientId());
        } catch (Exception e) {
            log.warn("Could not load the callback of client '{}', dropping the status update for onboarding {}",
                    after.clientId(), after.id(), e);
            return;
        }
        if (callback == null) {
            log.warn("No callback registered for client '{}', dropping the status update for onboarding {}",
                    after.clientId(), after.id());
            return;
        }
        var regData = new OspRegistrationCallbackData(after.externalId(), RegistrationStatus.from(after.state()), after.failureReason());
        post(after.clientId(), callback, regData);
    }

    /** Fire and forget: an unreachable provider (or token endpoint) is logged, never propagated. */
    private void post(String clientId, CallbackRequestData callback, OspRegistrationCallbackData callbackData) {
        try {
            //todo: potentially use an injected, managed/pooled builder?
            var request = RestClient.builder()
                    .baseUrl(callback.callbackUrl())
                    .build()
                    .post();
            if (callback.authUrl() != null && !callback.authUrl().isBlank()) {
                request.header("Authorization", "Bearer " + fetchToken(callback));
            }
            request.body(callbackData)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Error invoking callback for client '{}'", clientId, e);
        }
    }

    /**
     * OAuth2 client_credentials at the token endpoint the provider registered, authenticated with
     * the client id/secret it registered alongside — the credentials travel with the callback
     * registration (CX-0006 {@code OnboardingServiceProviderCallbackRequestData}).
     */
    private static String fetchToken(CallbackRequestData callback) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        var response = RestClient.builder()
                .baseUrl(callback.authUrl())
                .build()
                .post()
                .headers(headers -> headers.setBasicAuth(callback.clientId(), callback.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(TokenResponse.class);
        return Objects.requireNonNull(response, "empty token response").accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
