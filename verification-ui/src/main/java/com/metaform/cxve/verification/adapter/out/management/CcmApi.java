package com.metaform.cxve.verification.adapter.out.management;

import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A certificate management API as CX-0135 identifies it in a catalog: not by asset id, which is
 * every participant's own choice, but by three asset properties —
 * {@code dct:type} {@code cx-taxo:CCMAPI}, a {@code dct:subject} naming the API, and
 * {@code cx-common:version}. The standard allows one asset per subject and version per business
 * partner, so a catalog either offers the API exactly once, not yet, or in violation.
 *
 * <p>Written with full IRIs: the management API accepts only context URLs, not inline prefix
 * definitions, so prefixed names in an asset body would not expand.
 */
public record CcmApi(String subject, String version) {

    public static final String TAXONOMY = "https://w3id.org/catenax/taxonomy#";
    public static final String TYPE = TAXONOMY + "CCMAPI";
    /** The API a certificate provider offers: retrieve certificates, receive acceptance feedback. */
    public static final String PROVIDER_API = TAXONOMY + "CompanyCertificateManagementProviderApi";
    /** The API a certificate consumer offers: receive certificate lifecycle notifications (the push). */
    public static final String CONSUMER_API = TAXONOMY + "CompanyCertificateManagementConsumerApi";

    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String COMMON = "https://w3id.org/catenax/ontology/common#";

    public static CcmApi provider(String version) {
        return new CcmApi(PROVIDER_API, version);
    }

    public static CcmApi consumer(String version) {
        return new CcmApi(CONSUMER_API, version);
    }

    /** The asset properties that make an asset offer this API. */
    ObjectNode assetProperties(ObjectMapper mapper) {
        var properties = mapper.createObjectNode();
        properties.putObject(DCT + "type").put("@id", TYPE);
        properties.putObject(DCT + "subject").put("@id", subject);
        properties.put(COMMON + "version", version);
        return properties;
    }

    /**
     * Whether a catalog dataset offers this API. Tolerant of how the catalog was compacted — a
     * property may come back under its full IRI or a prefixed name, as a plain string or an
     * {@code @id}/{@code @value} object, possibly wrapped in an array — since the compaction is the
     * management API's, not the counterparty's.
     */
    boolean offeredBy(JsonNode dataset) {
        return TYPE.equals(iri(property(dataset, DCT, "dct", "type")))
                && subject.equals(iri(property(dataset, DCT, "dct", "subject")))
                && version.equals(literal(property(dataset, COMMON, "cx-common", "version")));
    }

    /** What a dataset offers, for diagnostics: its id and whatever API identity it declares. */
    static String describe(JsonNode dataset) {
        var parts = new ArrayList<String>();
        var subject = iri(property(dataset, DCT, "dct", "subject"));
        if (subject != null) {
            parts.add(localName(subject));
        }
        var version = literal(property(dataset, COMMON, "cx-common", "version"));
        if (version != null) {
            parts.add(version);
        }
        var id = dataset.path("@id").asText();
        return parts.isEmpty() ? id + " (no CX-0135 API subject)" : "%s (%s)".formatted(id, String.join(" ", parts));
    }

    @Override
    public String toString() {
        return "%s %s".formatted(localName(subject), version);
    }

    private static JsonNode property(JsonNode dataset, String namespace, String prefix, String name) {
        for (var key : new String[] { namespace + name, prefix + ":" + name }) {
            var value = dataset.path(key);
            if (!value.isMissingNode()) {
                return value.isArray() ? value.path(0) : value;
            }
        }
        return null;
    }

    private static String iri(JsonNode value) {
        if (value == null) {
            return null;
        }
        var raw = value.isObject() ? value.path("@id").asText(null) : value.asText(null);
        return raw != null && raw.startsWith("cx-taxo:") ? TAXONOMY + raw.substring("cx-taxo:".length()) : raw;
    }

    private static String literal(JsonNode value) {
        if (value == null) {
            return null;
        }
        return value.isObject() ? value.path("@value").asText(null) : value.asText(null);
    }

    private static String localName(String iri) {
        var hash = iri.lastIndexOf('#');
        return hash < 0 ? iri : iri.substring(hash + 1);
    }
}
