package com.metaform.cxve.verification.adapter.out.management;

import com.metaform.cxve.verification.config.VerificationProperties.CcmApiVocabulary;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A certificate exchange API as a dataspace's CCM standard identifies it in a catalog (Certo's
 * current release: DSAF SCE): not by asset id, which is every participant's own choice, but by the
 * dataset's {@code dct:conformsTo} value, which names the API and the specification's major
 * version. The standard has a participant publish one dataset per API, so a catalog either offers
 * the API exactly once, not yet, or in violation. The conformance IRIs are the dataspace's
 * ({@link CcmApiVocabulary}).
 *
 * <p>Written with full IRIs: the management API accepts only context URLs, not inline prefix
 * definitions, so prefixed names in an asset body would not expand.
 */
public record CcmApi(String conformsTo) {

    private static final String CONFORMS_TO = "http://purl.org/dc/terms/conformsTo";

    /** The API a certificate provider offers: answer requests, serve certificates, receive acceptance feedback. */
    public static CcmApi provider(CcmApiVocabulary vocabulary) {
        return new CcmApi(vocabulary.providerApi());
    }

    /** The API a certificate consumer offers: receive lifecycle and fulfillment notifications. */
    public static CcmApi consumer(CcmApiVocabulary vocabulary) {
        return new CcmApi(vocabulary.consumerApi());
    }

    /** The asset properties that make an asset offer this API. */
    ObjectNode assetProperties(ObjectMapper mapper) {
        var properties = mapper.createObjectNode();
        properties.putObject(CONFORMS_TO).put("@id", conformsTo);
        return properties;
    }

    /**
     * Whether a catalog dataset offers this API. Tolerant of how the catalog was compacted — the
     * property may come back under its full IRI, a prefixed name or the bare term the DSP 2025-1
     * context defines for it (what a live catalog returns), as a plain string or an {@code @id}
     * object, possibly in an array — since the compaction is the management API's, not the
     * counterparty's.
     */
    boolean offeredBy(JsonNode dataset) {
        for (var value : conformsToValues(dataset)) {
            if (conformsTo.equals(iri(value))) {
                return true;
            }
        }
        return false;
    }

    /** What a dataset offers, for diagnostics: its id and whatever conformance it declares. */
    String describe(JsonNode dataset) {
        var declared = new StringBuilder();
        for (var value : conformsToValues(dataset)) {
            var iri = iri(value);
            if (iri != null) {
                declared.append(declared.isEmpty() ? "" : " ").append(iri);
            }
        }
        var id = dataset.path("@id").asText();
        return declared.isEmpty() ? id + " (no dct:conformsTo)" : "%s (%s)".formatted(id, declared);
    }

    @Override
    public String toString() {
        return conformsTo;
    }

    private static Iterable<JsonNode> conformsToValues(JsonNode dataset) {
        for (var key : new String[]{CONFORMS_TO, "dct:conformsTo", "conformsTo"}) {
            var value = dataset.path(key);
            if (!value.isMissingNode()) {
                return value.isArray() ? value : List.of(value);
            }
        }
        return List.of();
    }

    private static String iri(JsonNode value) {
        return value.isObject() ? value.path("@id").asText(null) : value.asText(null);
    }
}
