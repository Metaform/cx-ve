package com.metaform.cxve.verification.adapter.out.management;

import com.metaform.cxve.verification.config.VerificationProperties.CcmApiVocabulary;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A certificate management API as a dataspace's CCM standard identifies it in a catalog (Catena-X:
 * CX-0135): not by asset id, which is every participant's own choice, but by three asset
 * properties — {@code dct:type} {@code <taxonomy>CCMAPI}, a {@code dct:subject} naming the API,
 * and a version property. The standard allows one asset per subject and version per business
 * partner, so a catalog either offers the API exactly once, not yet, or in violation. The
 * vocabulary is the dataspace's ({@link CcmApiVocabulary}).
 *
 * <p>Written with full IRIs: the management API accepts only context URLs, not inline prefix
 * definitions, so prefixed names in an asset body would not expand.
 */
public record CcmApi(CcmApiVocabulary vocabulary, String subject) {

    /** The API a certificate provider offers: retrieve certificates, receive acceptance feedback. */
    public static final String PROVIDER_API = "CompanyCertificateManagementProviderApi";
    /** The API a certificate consumer offers: receive certificate lifecycle notifications (the push). */
    public static final String CONSUMER_API = "CompanyCertificateManagementConsumerApi";

    private static final String TYPE = "CCMAPI";
    private static final String DCT = "http://purl.org/dc/terms/";

    public static CcmApi provider(CcmApiVocabulary vocabulary) {
        return new CcmApi(vocabulary, vocabulary.taxonomy() + PROVIDER_API);
    }

    public static CcmApi consumer(CcmApiVocabulary vocabulary) {
        return new CcmApi(vocabulary, vocabulary.taxonomy() + CONSUMER_API);
    }

    public String version() {
        return vocabulary.version();
    }

    /** The asset properties that make an asset offer this API. */
    ObjectNode assetProperties(ObjectMapper mapper) {
        var properties = mapper.createObjectNode();
        properties.putObject(DCT + "type").put("@id", vocabulary.taxonomy() + TYPE);
        properties.putObject(DCT + "subject").put("@id", subject);
        properties.put(vocabulary.versionProperty(), version());
        return properties;
    }

    /**
     * Whether a catalog dataset offers this API. Tolerant of how the catalog was compacted — a
     * property may come back under its full IRI or a prefixed name, as a plain string or an
     * {@code @id}/{@code @value} object, possibly wrapped in an array — since the compaction is the
     * management API's, not the counterparty's.
     */
    boolean offeredBy(JsonNode dataset) {
        return (vocabulary.taxonomy() + TYPE).equals(iri(property(dataset, DCT + "type", "dct:type")))
                && subject.equals(iri(property(dataset, DCT + "subject", "dct:subject")))
                && version().equals(literal(versionOf(dataset)));
    }

    /** What a dataset offers, for diagnostics: its id and whatever API identity it declares. */
    String describe(JsonNode dataset) {
        var parts = new ArrayList<String>();
        var declared = iri(property(dataset, DCT + "subject", "dct:subject"));
        if (declared != null) {
            parts.add(localName(declared));
        }
        var version = literal(versionOf(dataset));
        if (version != null) {
            parts.add(version);
        }
        var id = dataset.path("@id").asText();
        return parts.isEmpty() ? id + " (no CCM API subject)" : "%s (%s)".formatted(id, String.join(" ", parts));
    }

    @Override
    public String toString() {
        return "%s %s".formatted(localName(subject), version());
    }

    private JsonNode versionOf(JsonNode dataset) {
        var property = vocabulary.versionProperty();
        var compacted = vocabulary.versionPrefix() == null ? null
                : vocabulary.versionPrefix() + ":" + localName(property);
        return property(dataset, property, compacted);
    }

    private static JsonNode property(JsonNode dataset, String... keys) {
        for (var key : keys) {
            if (key == null) {
                continue;
            }
            var value = dataset.path(key);
            if (!value.isMissingNode()) {
                return value.isArray() ? value.path(0) : value;
            }
        }
        return null;
    }

    private String iri(JsonNode value) {
        if (value == null) {
            return null;
        }
        var raw = value.isObject() ? value.path("@id").asText(null) : value.asText(null);
        var prefix = vocabulary.taxonomyPrefix() == null ? null : vocabulary.taxonomyPrefix() + ":";
        return raw != null && prefix != null && raw.startsWith(prefix)
                ? vocabulary.taxonomy() + raw.substring(prefix.length())
                : raw;
    }

    private static String literal(JsonNode value) {
        if (value == null) {
            return null;
        }
        return value.isObject() ? value.path("@value").asText(null) : value.asText(null);
    }

    private static String localName(String iri) {
        var cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut < 0 ? iri : iri.substring(cut + 1);
    }
}
