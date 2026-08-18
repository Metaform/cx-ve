package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRegistrationStatusServiceTest {

    private final InMemoryRegistrationStatusService service = new InMemoryRegistrationStatusService();

    private static CallbackRequestData callback(String url) {
        return new CallbackRequestData(url, "https://auth.example/token", "osp-oauth-client", "secret");
    }

    @Test
    void eachClientKeepsItsOwnCallback() {
        // The reason for the map: a second provider registering must not overwrite the first.
        service.setCallbackAddress("client-1", callback("https://one.example/status"));
        service.setCallbackAddress("client-2", callback("https://two.example/status"));

        assertThat(service.getCallbackAddress("client-1").callbackUrl()).isEqualTo("https://one.example/status");
        assertThat(service.getCallbackAddress("client-2").callbackUrl()).isEqualTo("https://two.example/status");
        assertThat(service.getCallbackAddress("client-3")).isNull();
    }

    @Test
    void reRegistering_overwritesOnlyTheSameClient() {
        service.setCallbackAddress("client-1", callback("https://one.example/status"));
        service.setCallbackAddress("client-2", callback("https://two.example/status"));

        service.setCallbackAddress("client-1", callback("https://one.example/status-v2"));

        assertThat(service.getCallbackAddress("client-1").callbackUrl()).isEqualTo("https://one.example/status-v2");
        assertThat(service.getCallbackAddress("client-2").callbackUrl()).isEqualTo("https://two.example/status");
    }

    @Test
    void theKeyIsTheGivenIdentity_neverThePayloadsClientId() {
        // The payload's clientId ("osp-oauth-client" in the fixture) is the provider's OAuth2
        // client for the outbound callback call — storing under it would let the payload choose
        // whose registration to overwrite.
        service.setCallbackAddress("client-1", callback("https://one.example/status"));

        assertThat(service.getCallbackAddress("client-1")).isNotNull();
        assertThat(service.getCallbackAddress("osp-oauth-client")).isNull();
    }

    @Test
    void instancesDoNotShareState() {
        // The single-callback implementation held its registration in a STATIC field; that must
        // not survive the conversion.
        service.setCallbackAddress("client-1", callback("https://one.example/status"));

        assertThat(new InMemoryRegistrationStatusService().getCallbackAddress("client-1")).isNull();
    }
}
