package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.DocumentTypeId;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.UniqueIdentifierId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NetworkController.class)
class NetworkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NetworkService networkService;

    @Test
    void registerPartner_returns200AndDelegatesToService() throws Exception {
        var body = """
                {
                  "name": "Acme Corp",
                  "city": "Berlin",
                  "streetName": "Musterstrasse",
                  "countryAlpha2Code": "DE",
                  "region": "BE",
                  "externalId": "ext-123",
                  "uniqueIds": [
                    { "type": "VAT_ID", "value": "DE123456789" }
                  ],
                  "userDetails": [
                    {
                      "providerId": "prov-1",
                      "username": "jdoe",
                      "firstName": "John",
                      "lastName": "Doe",
                      "email": "john.doe@acme.example"
                    }
                  ],
                  "companyRoles": [ "ACTIVE_PARTICIPANT", "ONBOARDING_SERVICE_PROVIDER" ],
                  "documents": [
                    {
                      "documentType": "COMMERCIAL_REGISTER_EXTRACT",
                      "fileName": "extract.pdf",
                      "fileContent": "aGVsbG8="
                    }
                  ],
                  "autoSubmit": true
                }
                """;

        mockMvc.perform(post("/api/v2/administration/registration/Network/partnerRegistration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(PartnerRegistrationData.class);
        verify(networkService).registerPartner(captor.capture());

        var data = captor.getValue();
        assertThat(data.name()).isEqualTo("Acme Corp");
        assertThat(data.countryAlpha2Code()).isEqualTo("DE");
        assertThat(data.externalId()).isEqualTo("ext-123");
        assertThat(data.companyRoles()).containsExactly(CompanyRoleId.ACTIVE_PARTICIPANT, CompanyRoleId.ONBOARDING_SERVICE_PROVIDER);
        assertThat(data.uniqueIds()).hasSize(1);
        assertThat(data.uniqueIds().get(0).type()).isEqualTo(UniqueIdentifierId.VAT_ID);
        assertThat(data.uniqueIds().get(0).value()).isEqualTo("DE123456789");
        assertThat(data.userDetails()).hasSize(1);
        assertThat(data.userDetails().get(0).email()).isEqualTo("john.doe@acme.example");
        assertThat(data.documents()).hasSize(1);
        assertThat(data.documents().get(0).documentType()).isEqualTo(DocumentTypeId.COMMERCIAL_REGISTER_EXTRACT);
        assertThat(data.documents().get(0).fileContent()).asString().isEqualTo("hello");
        assertThat(data.autoSubmit()).isTrue();
    }

    @Test
    void registerPartner_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v2/administration/registration/Network/partnerRegistration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerPartner_withUnknownEnumValue_returns400() throws Exception {
        mockMvc.perform(post("/api/v2/administration/registration/Network/partnerRegistration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "companyRoles": [ "NOT_A_ROLE" ] }
                                """))
                .andExpect(status().isBadRequest());
    }
}
