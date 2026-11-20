package com.metaform.cxve.hub.e2e;

import java.util.List;

/**
 * What differs between the dataspaces the suite runs in: how a member is registered through the
 * Membership Hub, whether it brings its member id, the DSP profile its connector is dialled under
 * and the policies its offers carry. Everything else — provisioning, the DSP flows, the SCE
 * certificate exchange on Certo — is the same in both, which is the point of running the same
 * tests in each.
 */
enum E2eDataspace {

    /**
     * DECADE-X, through the dx-onboarding-api (the TSP onboarding intake, polled by the hub). The
     * TSP assigns the DECADE-X-ID — the hub generates it for a member it hosts — and there is no
     * policy vocabulary yet, so offers are constraint-free.
     */
    DECADE_X("decade-x", "DECADE-X-ID", "decade-x", null, List.of(), List.of()) {
        @Override
        String memberIdFor(String shortName) {
            return null;
        }

        @Override
        String registration(String vatId) {
            // The TSP's onboarding request data, minus what the hub fills in itself (legal name,
            // DID, applicantReference) — the verification UI's DECADE-X registration template.
            return """
                    {
                      "legalEntity": {
                        "registrationCountry": "DE",
                        "companyType": "LIMITED_LIABILITY_COMPANY",
                        "registrationNumbers": [ { "scheme": "vatID", "value": "%s" } ],
                        "legalAddress": { "street": "Otto-Hahn-Ring 6", "postalCode": "81739", "locality": "Munich",
                                          "countryCode": "DE", "countryName": "Germany" }
                      },
                      "businessSites": [ { "siteName": "E2e Site",
                                           "mainAddress": { "street": "Otto-Hahn-Ring 6", "postalCode": "81739",
                                                            "locality": "Munich", "countryCode": "DE",
                                                            "countryName": "Germany" } } ],
                      "legalPerson": { "fullName": "E2e Tester", "email": "e2e-%s@example.com", "role": "Operator" },
                      "gtc": { "versionNumber": "1.0" },
                      "ucas": [ { "useCaseId": "product-conformity", "versionNumber": "1.0" } ],
                      "declarations": { "informationAccurate": true, "authorisedToAct": true, "evidenceMayBeRequested": true }
                    }""".formatted(vatId, vatId);
        }
    },

    /**
     * Catena-X, through the cx-onboarding-api (CX-0009 partner registration, confirmed through a
     * status callback to the hub). A member declares its BPN up front; offers carry the Catena-X
     * policies, whose leftOperands the catenax-profile chart backs with CEL expressions over the
     * Membership, Bpn and DataExchangeGovernance credentials. The certificate exchange itself is
     * DSAF SCE on Certo, as in DECADE-X — the VE's accepted deviation from CX-0135.
     */
    CATENA_X("catena-x", "BPN", "cx-neptune", "https://w3id.org/catenax/2025/9/policy/context.jsonld",
            List.of(new Constraint("Membership", "eq", "active")),
            List.of(new Constraint("FrameworkAgreement", "eq", "DataExchangeGovernance:1.0"),
                    new Constraint("UsagePurpose", "isAnyOf", "cx.pcf.base:1"),
                    new Constraint("DataUsageEndDefinition", "eq", "cx.dataUsageEnd.unlimited:1"))) {
        /**
         * Derived from the run-unique short name: duplicates of a live membership's BPN are
         * rejected. The formula the verification UI's run form offers (bpn.ts).
         */
        @Override
        String memberIdFor(String shortName) {
            return ("BPNL" + String.format("%08X", Math.abs(shortName.hashCode())) + "000000").substring(0, 16);
        }

        @Override
        String registration(String vatId) {
            // The CX-0009 registration beyond the common fields. The ACTIVE "Catena-X" agreement
            // matters: it becomes the member's memberOf, which the Membership policy checks.
            return """
                    {
                      "city": "Munich",
                      "streetName": "Otto-Hahn-Ring",
                      "countryAlpha2Code": "DE",
                      "region": "BY",
                      "uniqueIds": [ { "type": "VAT_ID", "value": "%s" } ],
                      "companyRoles": [ "ACTIVE_PARTICIPANT" ],
                      "agreements": [ { "agreementId": "Catena-X", "consentStatus": "ACTIVE" } ],
                      "userDetails": [ {
                        "providerId": "e2e-user-%s",
                        "firstName": "E2e", "lastName": "Tester",
                        "email": "e2e-%s@example.com"
                      } ]
                    }""".formatted(vatId, vatId, vatId);
        }
    };

    /** A policy constraint; the leftOperand is a term of the dataspace's policy context. */
    record Constraint(String leftOperand, String operator, String rightOperand) {
    }

    private final String id;
    private final String memberIdLabel;
    private final String dspProfile;
    private final String policyContext;
    private final List<Constraint> accessConstraints;
    private final List<Constraint> contractConstraints;

    E2eDataspace(String id, String memberIdLabel, String dspProfile, String policyContext,
                 List<Constraint> accessConstraints, List<Constraint> contractConstraints) {
        this.id = id;
        this.memberIdLabel = memberIdLabel;
        this.dspProfile = dspProfile;
        this.policyContext = policyContext;
        this.accessConstraints = accessConstraints;
        this.contractConstraints = contractConstraints;
    }

    /** The id the Membership Hub knows the dataspace by. */
    String id() {
        return id;
    }

    /** What the dataspace calls a member id — for logs and failure messages. */
    String memberIdLabel() {
        return memberIdLabel;
    }

    /**
     * The DSP dataspace profile every catalog, negotiation and transfer request runs under, and
     * the last segment of a provider's DSP address. A DID document advertises only one profile
     * platform-wide, so the suite dials this one explicitly.
     */
    String dspProfile() {
        return dspProfile;
    }

    /** The JSON-LD context of the policy constraints' terms; null where offers carry none. */
    String policyContext() {
        return policyContext;
    }

    List<Constraint> accessConstraints() {
        return accessConstraints;
    }

    List<Constraint> contractConstraints() {
        return contractConstraints;
    }

    /**
     * The member id a member is submitted with, or null where the dataspace assigns it (the hub
     * then generates one for a member it hosts and reports it on the membership).
     */
    abstract String memberIdFor(String shortName);

    /** The dataspace-specific {@code registration} object of a hub member request, as JSON. */
    abstract String registration(String vatId);
}
