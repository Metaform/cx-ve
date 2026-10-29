package com.metaform.cxve.verification.adapter.out.management;

import com.metaform.cxve.verification.application.TestFixtureAccess;
import com.metaform.cxve.verification.config.VerificationProperties.CcmApiVocabulary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching a catalog dataset against a CCM API identity, in DSAF SCE's vocabulary (the dataset's
 * {@code dct:conformsTo}). The compaction of a catalog is the management API's business and may
 * differ between its versions, so the match must hold for every way JSON-LD lets the property be
 * written.
 */
class CcmApiTest {

    private static final CcmApiVocabulary SCE = TestFixtureAccess.CCM_API;
    private static final CcmApiVocabulary SCE_2 = new CcmApiVocabulary(
            "https://w3id.org/dsaf/sce/v2/provider-api", "https://w3id.org/dsaf/sce/v2/consumer-api");

    private final JsonMapper mapper = new JsonMapper();

    private JsonNode json(String json) {
        return mapper.readTree(json);
    }

    @Test
    void matchesTheCompactedFormTheManagementApiReturns() {
        var dataset = json("""
                {"@id": "x",
                 "dct:conformsTo": {"@id": "https://w3id.org/dsaf/sce/v1/provider-api"}}""");

        assertThat(CcmApi.provider(SCE).offeredBy(dataset)).isTrue();
        assertThat(CcmApi.consumer(SCE).offeredBy(dataset)).isFalse();
        assertThat(CcmApi.provider(SCE_2).offeredBy(dataset)).isFalse();
    }

    @Test
    void matchesTheDsp2025TermALiveCatalogCompactsTo() {
        // shape taken from a live catalog: the DSP 2025-1 context defines conformsTo as a bare term
        var dataset = json("""
                {"@id": "ccm-inbox-verification-dx", "@type": "Dataset",
                 "edc:name": "verification environment asset",
                 "conformsTo": "https://w3id.org/dsaf/sce/v1/consumer-api"}""");

        assertThat(CcmApi.consumer(SCE).offeredBy(dataset)).isTrue();
        assertThat(CcmApi.provider(SCE).offeredBy(dataset)).isFalse();
    }

    @Test
    void matchesFullIrisPlainStringsAndArrays() {
        var dataset = json("""
                {"@id": "x",
                 "http://purl.org/dc/terms/conformsTo": ["https://example.com/other",
                                                         {"@id": "https://w3id.org/dsaf/sce/v1/consumer-api"}]}""");

        assertThat(CcmApi.consumer(SCE).offeredBy(dataset)).isTrue();
        assertThat(CcmApi.consumer(SCE).offeredBy(json("""
                {"@id": "x", "dct:conformsTo": "https://w3id.org/dsaf/sce/v1/consumer-api"}"""))).isTrue();
    }

    @Test
    void aDatasetWithoutConformanceIsNotAnApiOffer() {
        assertThat(CcmApi.provider(SCE).offeredBy(json("""
                {"@id": "x", "dct:type": {"@id": "https://w3id.org/catenax/taxonomy#CCMAPI"}}"""))).isFalse();
    }

    @Test
    void theAssetPropertiesItWritesAreTheOnesItMatches() {
        var api = CcmApi.consumer(SCE);
        var dataset = api.assetProperties(mapper).put("@id", "inbox");

        assertThat(api.offeredBy(dataset)).isTrue();
    }

    @Test
    void describesADatasetByTheConformanceItDeclares() {
        assertThat(CcmApi.provider(SCE).describe(json("""
                {"@id": "inbox", "dct:conformsTo": {"@id": "https://w3id.org/dsaf/sce/v1/consumer-api"}}""")))
                .isEqualTo("inbox (https://w3id.org/dsaf/sce/v1/consumer-api)");
        assertThat(CcmApi.provider(SCE).describe(json("""
                {"@id": "ccm-api"}"""))).isEqualTo("ccm-api (no dct:conformsTo)");
        assertThat(CcmApi.provider(SCE)).hasToString("https://w3id.org/dsaf/sce/v1/provider-api");
    }
}
