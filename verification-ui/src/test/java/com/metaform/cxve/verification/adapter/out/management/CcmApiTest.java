package com.metaform.cxve.verification.adapter.out.management;

import com.metaform.cxve.verification.application.TestFixtureAccess;
import com.metaform.cxve.verification.config.VerificationProperties.CcmApiVocabulary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching a catalog dataset against a CCM API identity, in Catena-X's (CX-0135) vocabulary. The compaction of a catalog is the
 * management API's business and may differ between its versions, so the match must hold for every
 * way JSON-LD lets the same three properties be written.
 */
class CcmApiTest {

    private static final CcmApiVocabulary CX = TestFixtureAccess.CCM_API;
    private static final CcmApiVocabulary CX_4 = new CcmApiVocabulary(CX.taxonomy(), CX.taxonomyPrefix(),
            CX.versionProperty(), CX.versionPrefix(), "4.0");

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

        assertThat(CcmApi.provider(CX).offeredBy(dataset)).isTrue();
        assertThat(CcmApi.consumer(CX).offeredBy(dataset)).isFalse();
        assertThat(CcmApi.provider(CX_4).offeredBy(dataset)).isFalse();
    }

    @Test
    void matchesFullIrisPrefixedValuesArraysAndValueObjects() {
        var dataset = json("""
                {"@id": "x",
                 "http://purl.org/dc/terms/type": [{"@id": "cx-taxo:CCMAPI"}],
                 "http://purl.org/dc/terms/subject": "cx-taxo:CompanyCertificateManagementConsumerApi",
                 "cx-common:version": {"@value": "3.0"}}""");

        assertThat(CcmApi.consumer(CX).offeredBy(dataset)).isTrue();
    }

    @Test
    void aDatasetWithoutTheCcmTypeIsNotAnApiOffer() {
        // the subject alone does not make an API offer — CX-0135 types it as cx-taxo:CCMAPI
        var dataset = json("""
                {"@id": "x",
                 "dct:subject": {"@id": "https://w3id.org/catenax/taxonomy#CompanyCertificateManagementProviderApi"},
                 "https://w3id.org/catenax/ontology/common#version": "3.0"}""");

        assertThat(CcmApi.provider(CX).offeredBy(dataset)).isFalse();
    }

    @Test
    void anotherDataspacesVocabularyDoesNotMatchACatenaXOffer() {
        // the same API under another dataspace's taxonomy is a different identity
        var other = new CcmApiVocabulary("https://w3id.org/decadex/taxonomy#", "dx-taxo",
                "https://w3id.org/decadex/ontology/common#version", "dx-common", "3.0");
        var catenaXOffer = CcmApi.provider(CX).assetProperties(mapper).put("@id", "x");

        assertThat(CcmApi.provider(other).offeredBy(catenaXOffer)).isFalse();
        assertThat(CcmApi.provider(other).offeredBy(CcmApi.provider(other).assetProperties(mapper))).isTrue();
    }

    @Test
    void theAssetPropertiesItWritesAreTheOnesItMatches() {
        var api = CcmApi.consumer(CX);
        var dataset = api.assetProperties(mapper).put("@id", "inbox");

        assertThat(api.offeredBy(dataset)).isTrue();
    }

    @Test
    void describesADatasetByTheIdentityItDeclares() {
        assertThat(CcmApi.provider(CX).describe(json("""
                {"@id": "inbox", "dct:subject": {"@id": "https://w3id.org/catenax/taxonomy#CompanyCertificateManagementConsumerApi"},
                 "https://w3id.org/catenax/ontology/common#version": "3.0"}""")))
                .isEqualTo("inbox (CompanyCertificateManagementConsumerApi 3.0)");
        assertThat(CcmApi.provider(CX).describe(json("""
                {"@id": "ccm-api"}"""))).isEqualTo("ccm-api (no CCM API subject)");
        assertThat(CcmApi.provider(CX)).hasToString("CompanyCertificateManagementProviderApi 3.0");
    }
}
