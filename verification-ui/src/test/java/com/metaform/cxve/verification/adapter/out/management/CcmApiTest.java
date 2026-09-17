package com.metaform.cxve.verification.adapter.out.management;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching a catalog dataset against a CX-0135 API identity. The compaction of a catalog is the
 * management API's business and may differ between its versions, so the match must hold for every
 * way JSON-LD lets the same three properties be written.
 */
class CcmApiTest {

    private final JsonMapper mapper = new JsonMapper();

    private JsonNode json(String json) {
        return mapper.readTree(json);
    }

    @Test
    void matchesTheCompactedFormTheManagementApiReturns() {
        var dataset = json("""
                {"@id": "x",
                 "dct:type": {"@id": "https://w3id.org/catenax/taxonomy#CCMAPI"},
                 "dct:subject": {"@id": "https://w3id.org/catenax/taxonomy#CompanyCertificateManagementProviderApi"},
                 "https://w3id.org/catenax/ontology/common#version": "3.0"}""");

        assertThat(CcmApi.provider("3.0").offeredBy(dataset)).isTrue();
        assertThat(CcmApi.consumer("3.0").offeredBy(dataset)).isFalse();
        assertThat(CcmApi.provider("4.0").offeredBy(dataset)).isFalse();
    }

    @Test
    void matchesFullIrisPrefixedValuesArraysAndValueObjects() {
        var dataset = json("""
                {"@id": "x",
                 "http://purl.org/dc/terms/type": [{"@id": "cx-taxo:CCMAPI"}],
                 "http://purl.org/dc/terms/subject": "cx-taxo:CompanyCertificateManagementConsumerApi",
                 "cx-common:version": {"@value": "3.0"}}""");

        assertThat(CcmApi.consumer("3.0").offeredBy(dataset)).isTrue();
    }

    @Test
    void aDatasetWithoutTheCcmTypeIsNotAnApiOffer() {
        // the subject alone does not make an API offer — CX-0135 types it as cx-taxo:CCMAPI
        var dataset = json("""
                {"@id": "x",
                 "dct:subject": {"@id": "https://w3id.org/catenax/taxonomy#CompanyCertificateManagementProviderApi"},
                 "https://w3id.org/catenax/ontology/common#version": "3.0"}""");

        assertThat(CcmApi.provider("3.0").offeredBy(dataset)).isFalse();
    }

    @Test
    void theAssetPropertiesItWritesAreTheOnesItMatches() {
        var api = CcmApi.consumer("3.0");
        var dataset = api.assetProperties(mapper).put("@id", "inbox");

        assertThat(api.offeredBy(dataset)).isTrue();
    }

    @Test
    void describesADatasetByTheIdentityItDeclares() {
        assertThat(CcmApi.describe(json("""
                {"@id": "inbox", "dct:subject": {"@id": "https://w3id.org/catenax/taxonomy#CompanyCertificateManagementConsumerApi"},
                 "https://w3id.org/catenax/ontology/common#version": "3.0"}""")))
                .isEqualTo("inbox (CompanyCertificateManagementConsumerApi 3.0)");
        assertThat(CcmApi.describe(json("""
                {"@id": "ccm-api"}"""))).isEqualTo("ccm-api (no CX-0135 API subject)");
        assertThat(CcmApi.provider("3.0")).hasToString("CompanyCertificateManagementProviderApi 3.0");
    }
}
