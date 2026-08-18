package com.metaform.cxve.adapter.out.callback;

import com.metaform.cxve.domain.model.CallbackRequestData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRegistrationStatusServiceTest {

    private final InMemoryRegistrationStatusService service = new InMemoryRegistrationStatusService();

    private static CallbackRequestData callback(String clientId, String url) {
        return new CallbackRequestData(url, "https://auth.example/token", clientId, "secret");
    }

    @Test
    void eachClientKeepsItsOwnCallback() {
        // The reason for the map: a second provider registering must not overwrite the first.
        service.setCallbackAddress(callback("client-1", "https://one.example/status"));
        service.setCallbackAddress(callback("client-2", "https://two.example/status"));

        assertThat(service.getCallbackAddress("client-1").callbackUrl()).isEqualTo("https://one.example/status");
        assertThat(service.getCallbackAddress("client-2").callbackUrl()).isEqualTo("https://two.example/status");
        assertThat(service.getCallbackAddress("client-3")).isNull();
    }

    @Test
    void reRegistering_overwritesOnlyTheSameClient() {
        service.setCallbackAddress(callback("client-1", "https://one.example/status"));
        service.setCallbackAddress(callback("client-2", "https://two.example/status"));

        service.setCallbackAddress(callback("client-1", "https://one.example/status-v2"));

        assertThat(service.getCallbackAddress("client-1").callbackUrl()).isEqualTo("https://one.example/status-v2");
        assertThat(service.getCallbackAddress("client-2").callbackUrl()).isEqualTo("https://two.example/status");
    }

    @Test
    void aRegistrationWithoutAClientId_occupiesTheAnonymousSlot() {
        // The pre-multi-client contract for providers that do not identify themselves (the e2e
        // suite registers this way): one anonymous registration, replaced by the next one, and
        // readable back with a null client id.
        service.setCallbackAddress(callback(null, "https://anon.example/status"));
        service.setCallbackAddress(callback(null, "https://anon.example/status-v2"));

        assertThat(service.getCallbackAddress(null).callbackUrl()).isEqualTo("https://anon.example/status-v2");
    }

    @Test
    void instancesDoNotShareState() {
        // The single-callback implementation held its registration in a STATIC field; that must
        // not survive the conversion.
        service.setCallbackAddress(callback("client-1", "https://one.example/status"));

        assertThat(new InMemoryRegistrationStatusService().getCallbackAddress("client-1")).isNull();
    }
}
