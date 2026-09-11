package com.metaform.cxve.verification.adapter.out.http;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Status-transparent calls on a {@link RestClient}: {@code exchange()} instead of
 * {@code retrieve()}, so a 4xx/5xx comes back as a plain {@link HttpResult} rather than an
 * exception — the clients ported from the e2e suite interpret status codes themselves (409 =
 * already exists, 4xx = abort, 5xx = retry).
 */
public final class RestCalls {

    private RestCalls() {
    }

    /** POST with a JSON body; {@code bearerToken} may be null for unauthenticated APIs. */
    public static HttpResult post(RestClient client, String path, String bearerToken, String jsonBody) {
        var request = client.post().uri(path);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        return request
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .exchange((req, response) -> new HttpResult(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** POST without a body (some CCM actions are addressed purely by path + query). */
    public static HttpResult post(RestClient client, String path, String bearerToken) {
        var request = client.post().uri(path);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        return request.exchange((req, response) -> new HttpResult(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    public static HttpResult get(RestClient client, String path, String bearerToken) {
        var request = client.get().uri(path);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        return request.exchange((req, response) -> new HttpResult(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}
