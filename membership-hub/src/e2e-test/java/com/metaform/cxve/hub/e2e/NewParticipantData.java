package com.metaform.cxve.hub.e2e;

import java.util.ArrayList;
import java.util.List;

/**
 * E2e-local mirror of the app's {@code PartnerRegistrationData} wire format (the payload of
 * POST /api/v2/administration/registration/Network/partnerRegistration). Kept separate on
 * purpose: the e2e suite has no dependency on the app's classes, so these tests exercise the
 * HTTP contract exactly as an external client would. Enums of the main model are flattened to
 * plain strings here (e.g. companyRoles "ACTIVE_PARTICIPANT", consentStatus "ACTIVE") — keep
 * the fields in sync with the main DTO manually.
 */
public record NewParticipantData(
        String name,
        String city,
        String streetName,
        String countryAlpha2Code,
        String bpn,
        String shortName,
        String region,
        String streetAdditional,
        String streetNumber,
        String zipCode,
        List<UniqueId> uniqueIds,
        String externalId,
        List<UserDetail> userDetails,
        List<String> companyRoles,
        String did,
        List<Agreement> agreements,
        List<Document> documents,
        Boolean autoSubmit
) {

    public record UniqueId(String type, String value) {
    }

    public record UserDetail(
            String identityProviderId,
            String providerId,
            String username,
            String firstName,
            String lastName,
            String email
    ) {
    }

    public record Agreement(String agreementId, String consentStatus) {
    }

    /** {@code fileContent} is base64-encoded on the wire (Jackson's byte[] mapping in the app). */
    public record Document(String documentType, String fileName, byte[] fileContent) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String city;
        private String streetName;
        private String countryAlpha2Code;
        private String bpn;
        private String shortName;
        private String region;
        private String streetAdditional;
        private String streetNumber;
        private String zipCode;
        private final List<UniqueId> uniqueIds = new ArrayList<>();
        private String externalId;
        private final List<UserDetail> userDetails = new ArrayList<>();
        private final List<String> companyRoles = new ArrayList<>();
        private String did;
        private final List<Agreement> agreements = new ArrayList<>();
        private final List<Document> documents = new ArrayList<>();
        private Boolean autoSubmit;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder city(String city) {
            this.city = city;
            return this;
        }

        public Builder streetName(String streetName) {
            this.streetName = streetName;
            return this;
        }

        public Builder countryAlpha2Code(String countryAlpha2Code) {
            this.countryAlpha2Code = countryAlpha2Code;
            return this;
        }

        public Builder bpn(String bpn) {
            this.bpn = bpn;
            return this;
        }

        public Builder shortName(String shortName) {
            this.shortName = shortName;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder streetAdditional(String streetAdditional) {
            this.streetAdditional = streetAdditional;
            return this;
        }

        public Builder streetNumber(String streetNumber) {
            this.streetNumber = streetNumber;
            return this;
        }

        public Builder zipCode(String zipCode) {
            this.zipCode = zipCode;
            return this;
        }

        public Builder uniqueId(String type, String value) {
            this.uniqueIds.add(new UniqueId(type, value));
            return this;
        }

        public Builder externalId(String externalId) {
            this.externalId = externalId;
            return this;
        }

        public Builder userDetail(UserDetail userDetail) {
            this.userDetails.add(userDetail);
            return this;
        }

        public Builder companyRole(String companyRole) {
            this.companyRoles.add(companyRole);
            return this;
        }

        public Builder did(String did) {
            this.did = did;
            return this;
        }

        public Builder agreement(String agreementId, String consentStatus) {
            this.agreements.add(new Agreement(agreementId, consentStatus));
            return this;
        }

        public Builder document(Document document) {
            this.documents.add(document);
            return this;
        }

        public Builder autoSubmit(Boolean autoSubmit) {
            this.autoSubmit = autoSubmit;
            return this;
        }

        public NewParticipantData build() {
            return new NewParticipantData(name, city, streetName, countryAlpha2Code, bpn, shortName,
                    region, streetAdditional, streetNumber, zipCode, List.copyOf(uniqueIds), externalId,
                    List.copyOf(userDetails), List.copyOf(companyRoles), did, List.copyOf(agreements),
                    List.copyOf(documents), autoSubmit);
        }
    }
}
