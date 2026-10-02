package com.metaform.dxonboarding.adapter.out.auth;

import com.metaform.dxonboarding.config.TokenProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The workload token is exchanged at the jwtlet under the requested mapping and scopes. */
class WorkloadTokenProviderTest {

    @TempDir
    Path tokenDir;

    @Test
    void exchangesTheProjectedTokenForAScopedOne() throws IOException {
        var tokenFile = Files.writeString(tokenDir.resolve("token"), "k8s-sa-token");
        var builder = RestClient.builder().baseUrl("http://jwtlet.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new WorkloadTokenProvider(new TokenProperties(new TokenProperties.File(tokenFile.toString()),
                new TokenProperties.Exchange("http://jwtlet.test", "edcv")), builder.build());

        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.add("subject_token", "k8s-sa-token");
        form.add("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
        form.add("audience", "edcv");
        form.add("resource", "sudo");
        form.add("scope", "issuer-admin-api:admin");
        server.expect(requestTo("http://jwtlet.test/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form))
                .andRespond(withSuccess("""
                        {"access_token": "scoped", "token_type": "Bearer"}""", MediaType.APPLICATION_JSON));

        assertThat(provider.getToken("sudo", "issuer-admin-api:admin")).isEqualTo("scoped");
        server.verify();
    }
}
