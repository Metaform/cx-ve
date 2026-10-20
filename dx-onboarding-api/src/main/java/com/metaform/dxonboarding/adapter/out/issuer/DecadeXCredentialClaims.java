package com.metaform.dxonboarding.adapter.out.issuer;

import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.BusinessSite;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.RegistrationNumber;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The claims of an approved participant's DECADE-X credentials, as the holder properties the
 * issuer's credential definitions copy into them whole: the {@code DX_LegalEntity_VC}'s
 * {@code credentialSubject.partner} ({@link #LEGAL_ENTITY}) and the {@code DX_Site_VC}'s
 * ({@link #SITE}), both with {@code credentialSubject.type} {@code dx:Wallet} ({@link #SUBJECT_TYPE}).
 * Shaped like {@code examples/credentials}: each partner carries its own JSON-LD context, its DECADE-X
 * partner id as {@code partnerID} (the legal entity's is its DECADE-X-ID) and its addresses as
 * Gaia-X addresses.
 *
 * <p>The issuer generates ONE credential per definition and holder, so a participant gets a Site VC
 * for its first business site only, and none when it declared no site.
 */
final class DecadeXCredentialClaims {

    /** The holder property the Legal Entity VC's partner is copied from. */
    static final String LEGAL_ENTITY = "dxLegalEntity";
    /** The holder property the Site VC's partner is copied from. */
    static final String SITE = "dxSite";
    /** The holder property both credentials' {@code credentialSubject.type} is copied from. */
    static final String SUBJECT_TYPE = "dxSubjectType";

    private static final String DX = "https://vocabulary.decade-x.org/ontology/participant/0#";
    private static final Map<String, Object> PARTNER_CONTEXT = context();

    private DecadeXCredentialClaims() {
    }

    /** The holder properties of the request's participant, which must have its DECADE-X-ID. */
    static Map<String, Object> of(OnboardingRequest request) {
        var decadeXId = Objects.requireNonNull(request.legalEntityId(), "the request has no DECADE-X-ID yet");
        var claims = new LinkedHashMap<String, Object>();
        claims.put("id", request.connectorId());
        claims.put(SUBJECT_TYPE, "dx:Wallet");
        claims.put(LEGAL_ENTITY, legalEntity(request, decadeXId));
        firstSite(request).ifPresent(site -> claims.put(SITE, site(site, decadeXId)));
        return claims;
    }

    /** The business site the Site VC is issued for, if the request declares any. */
    static Optional<BusinessSite> firstSite(OnboardingRequest request) {
        return request.data().businessSites().stream().findFirst();
    }

    private static Map<String, Object> legalEntity(OnboardingRequest request, String decadeXId) {
        var entity = request.data().legalEntity();
        var partner = partner(decadeXId, "LegalEntity");
        putIfPresent(partner, "dx:name", entity.legalName());
        partner.put("partnerID", decadeXId);
        putIfPresent(partner, "registrationNumber", registrationNumbers(entity.registrationNumbers()));
        putIfPresent(partner, "legalAddress", address(entity.legalAddress()));
        return partner;
    }

    /** A site's partner id: its {@code siteId}, or one derived from the legal entity's when it has none. */
    private static Map<String, Object> site(BusinessSite site, String decadeXId) {
        var partnerId = site.siteId() == null || site.siteId().isBlank() ? decadeXId + "-S1" : site.siteId();
        var partner = partner(partnerId, "BusinessSite");
        putIfPresent(partner, "siteName", site.siteName());
        partner.put("partnerID", partnerId);
        putIfPresent(partner, "registrationNumber", registrationNumbers(site.registrationNumbers()));
        partner.put("ownedBy_Dx", Map.of("id", "dx:" + decadeXId, "type", "LegalEntity"));
        putIfPresent(partner, "mainAddress", address(site.mainAddress()));
        putIfPresent(partner, "secondaryAddress", address(site.secondaryAddress()));
        return partner;
    }

    private static Map<String, Object> partner(String partnerId, String type) {
        var partner = new LinkedHashMap<String, Object>();
        partner.put("@context", PARTNER_CONTEXT);
        partner.put("id", "dx:" + partnerId);
        partner.put("type", type);
        return partner;
    }

    /** Each number as {@code {<scheme>: <value>}}; null when there are none. */
    private static List<Map<String, String>> registrationNumbers(List<RegistrationNumber> numbers) {
        return numbers.isEmpty() ? null : numbers.stream()
                .map(number -> Map.of(number.scheme().name(), number.value()))
                .toList();
    }

    private static Map<String, Object> address(Address address) {
        if (address == null) {
            return null;
        }
        var gx = new LinkedHashMap<String, Object>();
        gx.put("type", "gx:Address");
        putIfPresent(gx, "countryName", address.countryName());
        if (address.countryCode() != null) {
            gx.put("countryCode", Map.of("type", countryCodeType(address.countryCode()), "code", address.countryCode()));
        }
        putIfPresent(gx, "streetAddress", address.street());
        putIfPresent(gx, "postalCode", address.postalCode());
        putIfPresent(gx, "locality", address.locality());
        return gx;
    }

    /** The Gaia-X country code kind: numeric (ISO 3166-1 numeric), alpha-3, or alpha-2. */
    private static String countryCodeType(String code) {
        if (code.chars().allMatch(Character::isDigit)) {
            return "gx:CountryNameNumeric";
        }
        return code.length() == 3 ? "gx:CountryNameAlpha3" : "gx:CountryNameAlpha2";
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static Map<String, Object> context() {
        var context = new LinkedHashMap<String, Object>();
        context.put("id", "@id");
        context.put("type", "@type");
        context.put("@vocab", DX);
        context.put("dx", DX);
        context.put("gx", "https://w3id.org/gaia-x/2411#");
        context.put("vcard", "https://www.w3.org/2006/vcard/ns#");
        context.put("legalAddress", "gx:legalAddress");
        context.put("countryName", "gx:countryName");
        context.put("countryCode", "gx:countryCode");
        context.put("streetAddress", "vcard:street-address");
        context.put("locality", "vcard:locality");
        context.put("postalCode", "vcard:postal-code");
        return Collections.unmodifiableMap(context);
    }
}
