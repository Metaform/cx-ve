package com.metaform.cxve.verification.adapter.out.hub;

import com.metaform.cxve.verification.application.TestFixtureAccess;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The lookup URLs, pinned. A DID is the one identifier here that can carry a percent-escape of
 * its own ({@code did:web:host%3A8080:ctx} — any non-default port), and encoding it a second time
 * on the way out turns the lookup into one for a participant that does not exist. That failure is
 * silent in the worst way: an empty result reads as "not onboarded yet", so the run onboards
 * again and is declined as a duplicate.
 */
class MembershipHubClientTest {

    private static final String DID_WITH_PORT = "did:web:sut.example.com%3A8080:tenant-9";

    private final JsonMapper mapper = new JsonMapper();

    @Test
    void findByDid_sendsTheDidEscapedExactlyOnce() {
        var builder = RestClient.builder().baseUrl("http://hub");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new MembershipHubClient(builder.build(), mapper, TestFixtureAccess.props(Map.of()));

        // The '%' of the DID's own escape becomes '%25'; everything else is escaped once. A
        // doubly-escaped '%2525' would not match the stored record.
        server.expect(requestTo("http://hub/api/members?did=did%3Aweb%3Asut.example.com%253A8080%3Atenant-9"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findByDid(DID_WITH_PORT)).isEmpty();
        server.verify();
    }

    @Test
    void findByBpn_isUnaffected() {
        var builder = RestClient.builder().baseUrl("http://hub");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new MembershipHubClient(builder.build(), mapper, TestFixtureAccess.props(Map.of()));

        server.expect(requestTo("http://hub/api/members?bpn=BPNL0000000000XY"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findByBpn("BPNL0000000000XY")).isEmpty();
        server.verify();
    }
}
