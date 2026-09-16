package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.application.MembershipService;
import com.metaform.cxve.hub.domain.model.Membership;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test of the member lookup: the BPN filter is required, unknown ids map to 404 — plus
 * the one request-shape rule the onboarding flow cannot enforce for itself.
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
    void findByBpn_returnsTheMatches() throws Exception {
        when(membershipService.findByBpn("BPNLONE000000001")).thenReturn(List.of(
                Membership.submitted("ext-1", "Acme Corp", "did:web:acme", "BPNLONE000000001")));

        mvc.perform(get("/api/members").param("bpn", "BPNLONE000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-1"))
                .andExpect(jsonPath("$[0].state").value("SUBMITTED"));
    }

    @Test
    void findByDid_returnsTheMatches() throws Exception {
        // The lookup an externally hosted member's operator can actually perform: it knows the
        // DID, not the external id this hub minted.
        when(membershipService.findByDid("did:web:sut.example.com")).thenReturn(List.of(
                Membership.submitted("ext-9", "SUT GmbH", "did:web:sut.example.com", "BPNL0000000000SU", true)));

        mvc.perform(get("/api/members").param("did", "did:web:sut.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-9"))
                .andExpect(jsonPath("$[0].externallyHosted").value(true));
    }

    @Test
    void find_requiresExactlyOneFilter() throws Exception {
        mvc.perform(get("/api/members"))
                .andExpect(status().isBadRequest());

        // Two filters would leave the intended semantics of the combination ambiguous.
        mvc.perform(get("/api/members")
                        .param("bpn", "BPNLONE000000001")
                        .param("did", "did:web:sut.example.com"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(membershipService);
    }

    @Test
    void onboard_rejectsAnExternallyHostedMemberWithoutADid() throws Exception {
        // Without a DID the hub would mint one under THIS environment's authority — an identity
        // a member hosted elsewhere does not control, and one the credential offer could never
        // reach. Rejected at the boundary rather than onboarded into a dead end.
        mvc.perform(post("/api/members")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SUT GmbH", "shortName": "sut", "bpn": "BPNL0000000000SU",
                                  "city": "Munich", "streetName": "Otto-Hahn-Ring",
                                  "countryAlpha2Code": "DE", "region": "BY",
                                  "externallyHosted": true,
                                  "uniqueIds": [ { "type": "VAT_ID", "value": "DE987654321" } ],
                                  "companyRoles": [ "ACTIVE_PARTICIPANT" ],
                                  "agreements": [ { "agreementId": "Catena-X", "consentStatus": "ACTIVE" } ],
                                  "userDetails": [ { "providerId": "prov-9", "firstName": "Jane",
                                                     "lastName": "Doe", "email": "jane.doe@sut.example" } ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(membershipService);
    }

    @Test
    void get_unknownExternalIdIs404() throws Exception {
        when(membershipService.get("no-such")).thenThrow(new NoSuchElementException("No membership"));

        mvc.perform(get("/api/members/no-such"))
                .andExpect(status().isNotFound());
    }
}
