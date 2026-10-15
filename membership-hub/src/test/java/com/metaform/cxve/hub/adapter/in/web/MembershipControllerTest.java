package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.application.MembershipService;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test of the member lookup: exactly one of the member-id and DID filters is required,
 * a member id only with its dataspace, unknown ids map to 404 — plus the request-shape rules the
 * onboarding flow cannot enforce for itself.
 */
// Security exclusion: see EventlogControllerTest — same slice, same reason.
@WebMvcTest(controllers = MembershipController.class,
        excludeAutoConfiguration = OAuth2ResourceServerWebSecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class MembershipControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MembershipService membershipService;

    @Test
    void findByMemberId_returnsTheMatches() throws Exception {
        when(membershipService.findByMemberId("catena-x", "BPNLONE000000001")).thenReturn(List.of(
                Membership.submitted("ext-1", "catena-x", "Acme Corp", "did:web:acme", "BPNLONE000000001")));

        mvc.perform(get("/api/members").param("dataspace", "catena-x").param("memberId", "BPNLONE000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-1"))
                .andExpect(jsonPath("$[0].dataspace").value("catena-x"))
                .andExpect(jsonPath("$[0].memberId").value("BPNLONE000000001"))
                .andExpect(jsonPath("$[0].state").value("SUBMITTED"));
    }

    @Test
    void findByMemberId_requiresTheDataspace() throws Exception {
        // Member ids are only unique within their dataspace.
        mvc.perform(get("/api/members").param("memberId", "BPNLONE000000001"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(membershipService);
    }

    @Test
    void findByDid_narrowsToTheDataspaceWhenGiven() throws Exception {
        when(membershipService.findByDid("did:web:sut.example.com")).thenReturn(List.of(
                Membership.submitted("ext-1", "catena-x", "SUT GmbH", "did:web:sut.example.com", "BPNL0000000000SU"),
                Membership.submitted("ext-2", "decade-x", "SUT GmbH", "did:web:sut.example.com", "DX-1")));

        mvc.perform(get("/api/members").param("did", "did:web:sut.example.com").param("dataspace", "decade-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalId").value("ext-2"));
    }

    @Test
    void findByDid_returnsTheMatches() throws Exception {
        // The lookup an externally hosted member's operator can actually perform: it knows the
        // DID, not the external id this hub minted.
        when(membershipService.findByDid("did:web:sut.example.com")).thenReturn(List.of(
                Membership.submitted("ext-9", "catena-x", "SUT GmbH", "did:web:sut.example.com", "BPNL0000000000SU")));

        mvc.perform(get("/api/members").param("did", "did:web:sut.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-9"))
                .andExpect(jsonPath("$[0].did").value("did:web:sut.example.com"));
    }

    @Test
    void find_requiresExactlyOneFilter() throws Exception {
        mvc.perform(get("/api/members"))
                .andExpect(status().isBadRequest());

        // Two filters would leave the intended semantics of the combination ambiguous.
        mvc.perform(get("/api/members")
                        .param("dataspace", "catena-x")
                        .param("memberId", "BPNLONE000000001")
                        .param("did", "did:web:sut.example.com"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(membershipService);
    }

    @Test
    void onboard_passesACallerSuppliedDidThrough() throws Exception {
        // The DID is what decides hosting: a member that brings one keeps its own identity and
        // nothing is provisioned for it here. The controller must hand it to the service
        // untouched — a dropped DID would silently turn a third-party system into a member this
        // environment tries to deploy.
        when(membershipService.onboard(any())).thenReturn(
                Membership.submitted("ext-9", "catena-x", "SUT GmbH", "did:web:sut.example.com", "BPNL0000000000SU"));

        mvc.perform(post("/api/members")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dataspace": "catena-x",
                                  "name": "SUT GmbH", "shortName": "sut", "memberId": "BPNL0000000000SU",
                                  "did": "did:web:sut.example.com",
                                  "registration": {
                                    "city": "Munich", "streetName": "Otto-Hahn-Ring",
                                    "countryAlpha2Code": "DE", "region": "BY",
                                    "uniqueIds": [ { "type": "VAT_ID", "value": "DE987654321" } ],
                                    "companyRoles": [ "ACTIVE_PARTICIPANT" ],
                                    "agreements": [ { "agreementId": "Catena-X", "consentStatus": "ACTIVE" } ],
                                    "userDetails": [ { "providerId": "prov-9", "firstName": "Jane",
                                                       "lastName": "Doe", "email": "jane.doe@sut.example" } ]
                                  }
                                }
                                """))
                .andExpect(status().isCreated());

        var submitted = ArgumentCaptor.forClass(MemberData.class);
        verify(membershipService).onboard(submitted.capture());
        assertThat(submitted.getValue().did()).isEqualTo("did:web:sut.example.com");
        assertThat(submitted.getValue().hostedHere()).isFalse();
        // The dataspace-specific part is handed on as-is, for the dataspace to read.
        assertThat(submitted.getValue().registration()).containsEntry("city", "Munich");
    }

    @Test
    void onboard_requiresTheCommonFields() throws Exception {
        mvc.perform(post("/api/members")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "SUT GmbH", "shortName": "sut", "memberId": "BPNL0000000000SU" }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(membershipService);
    }

    @Test
    void onboard_mapsAnUnservedDataspaceTo400() throws Exception {
        when(membershipService.onboard(any())).thenThrow(new DataspaceOnboarding.UnknownDataspaceException("decade-x"));

        mvc.perform(post("/api/members")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                { "dataspace": "decade-x", "name": "SUT GmbH", "shortName": "sut", "memberId": "DX-1" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_unknownExternalIdIs404() throws Exception {
        when(membershipService.get("no-such")).thenThrow(new NoSuchElementException("No membership"));

        mvc.perform(get("/api/members/no-such"))
                .andExpect(status().isNotFound());
    }
}
