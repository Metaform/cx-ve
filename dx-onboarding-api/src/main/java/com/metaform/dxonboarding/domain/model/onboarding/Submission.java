package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An onboarding request together with its documents, as one submission delivers them. A
 * submission is validated as a whole ({@link #violations()}): an incomplete one is refused and
 * nothing of it is stored, so the applicant can correct it and send it again.
 *
 * <p>Documents are told apart by their part name: {@value #GTC_DOCUMENT} (required),
 * {@value #REGISTRATION_EXTRACT_DOCUMENT} and {@value #POWER_OF_ATTORNEY_DOCUMENT} (optional), and
 * one {@code ucaDocument[<useCaseId>]} per entry of {@code ucas} (required).
 */
public record Submission(OnboardingRequestData request, List<SubmittedDocument> documents) {

    public static final String GTC_DOCUMENT = "gtcDocument";
    public static final String REGISTRATION_EXTRACT_DOCUMENT = "registrationExtractDocument";
    public static final String POWER_OF_ATTORNEY_DOCUMENT = "powerOfAttorneyDocument";

    public static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");
    public static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;
    public static final long MAX_SUBMISSION_BYTES = 25L * 1024 * 1024;

    private static final Pattern UCA_DOCUMENT = Pattern.compile("ucaDocument\\[(.+)]");

    public Submission {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /** The part name the signed UCA document of a use case arrives in. */
    public static String ucaDocumentPart(String useCaseId) {
        return "ucaDocument[" + useCaseId + "]";
    }

    public Optional<SubmittedDocument> document(String part) {
        return documents.stream().filter(document -> document.part().equals(part)).findFirst();
    }

    /** Everything that keeps this submission from being accepted; empty when it is complete. */
    public List<String> violations() {
        var violations = new ArrayList<String>();
        if (request == null) {
            violations.add("request: required");
        } else {
            checkLegalEntity(request.legalEntity(), violations);
            checkLegalPerson(request.legalPerson(), violations);
            for (int i = 0; i < request.businessSites().size(); i++) {
                checkRegistrationNumbers("businessSites[%d].registrationNumbers".formatted(i),
                        request.businessSites().get(i).registrationNumbers(), violations);
            }
            checkGtc(request.gtc(), violations);
            checkUcas(request.ucas(), violations);
            checkDeclarations(request.declarations(), violations);
        }
        checkDocuments(violations);
        return violations;
    }

    /**
     * What makes two submissions the same content: the request and every document's part, format
     * and bytes — not the file names. Sending the same content again is a retry, not a new request.
     */
    public Fingerprint fingerprint() {
        var digests = documents.stream().collect(Collectors.toMap(SubmittedDocument::part,
                document -> normalized(document.contentType()) + " " + document.digest(), (first, second) -> first,
                TreeMap::new));
        return new Fingerprint(request, digests);
    }

    public record Fingerprint(OnboardingRequestData request, Map<String, String> documents) {

        public Fingerprint {
            documents = Map.copyOf(documents);
        }
    }

    private static void checkLegalEntity(LegalEntity entity, List<String> violations) {
        if (entity == null) {
            violations.add("legalEntity: required");
            return;
        }
        requireText("legalEntity.legalName", entity.legalName(), violations);
        requireText("legalEntity.registrationCountry", entity.registrationCountry(), violations);
        if (entity.companyType() == null) {
            violations.add("legalEntity.companyType: required");
        } else if (entity.companyType() == CompanyType.OTHER) {
            requireText("legalEntity.companyTypeOther", entity.companyTypeOther(), violations);
        }
        if (entity.registrationNumbers().isEmpty()) {
            violations.add("legalEntity.registrationNumbers: at least one is required");
        }
        checkRegistrationNumbers("legalEntity.registrationNumbers", entity.registrationNumbers(), violations);
        var address = entity.legalAddress();
        if (address == null) {
            violations.add("legalEntity.legalAddress: required");
        } else {
            requireText("legalEntity.legalAddress.street", address.street(), violations);
            requireText("legalEntity.legalAddress.locality", address.locality(), violations);
            requireText("legalEntity.legalAddress.countryCode", address.countryCode(), violations);
        }
    }

    private void checkLegalPerson(LegalPerson person, List<String> violations) {
        if (person == null) {
            violations.add("legalPerson: required");
            return;
        }
        requireText("legalPerson.fullName", person.fullName(), violations);
        requireText("legalPerson.email", person.email(), violations);
        if (!blank(person.email()) && !person.email().matches("[^@\\s]+@[^@\\s]+")) {
            violations.add("legalPerson.email: not an email address");
        }
        if (document(POWER_OF_ATTORNEY_DOCUMENT).isPresent() && person.powerOfAttorneyValidUntil() == null) {
            violations.add("legalPerson.powerOfAttorneyValidUntil: required with a " + POWER_OF_ATTORNEY_DOCUMENT);
        }
    }

    private static void checkRegistrationNumbers(String path, List<RegistrationNumber> numbers, List<String> violations) {
        for (int i = 0; i < numbers.size(); i++) {
            var number = numbers.get(i);
            if (number == null || number.scheme() == null) {
                violations.add("%s[%d].scheme: required".formatted(path, i));
            }
            if (number == null || blank(number.value())) {
                violations.add("%s[%d].value: required".formatted(path, i));
            }
        }
    }

    private static void checkGtc(ConsentDeclaration gtc, List<String> violations) {
        if (gtc == null) {
            violations.add("gtc: required");
            return;
        }
        if (gtc.versionId() == null) {
            violations.add("gtc.versionId: required");
        }
        requireText("gtc.versionNumber", gtc.versionNumber(), violations);
        if (!Boolean.TRUE.equals(gtc.accepted())) {
            violations.add("gtc.accepted: the GTC must be accepted");
        }
    }

    private void checkUcas(List<UseCaseAgreement> ucas, List<String> violations) {
        var useCaseIds = new HashSet<String>();
        for (int i = 0; i < ucas.size(); i++) {
            var uca = ucas.get(i);
            if (uca == null || blank(uca.useCaseId())) {
                violations.add("ucas[%d].useCaseId: required".formatted(i));
                continue;
            }
            if (!useCaseIds.add(uca.useCaseId())) {
                violations.add("ucas[%d].useCaseId: '%s' is listed more than once".formatted(i, uca.useCaseId()));
            }
            if (!Boolean.TRUE.equals(uca.accepted())) {
                violations.add("ucas[%d].accepted: the use case agreement must be accepted".formatted(i));
            }
            if (document(ucaDocumentPart(uca.useCaseId())).isEmpty()) {
                violations.add("%s: required for use case '%s'".formatted(ucaDocumentPart(uca.useCaseId()), uca.useCaseId()));
            }
        }
        documents.stream()
                .map(document -> UCA_DOCUMENT.matcher(document.part()))
                .filter(matcher -> matcher.matches() && !useCaseIds.contains(matcher.group(1)))
                .forEach(matcher -> violations.add("%s: no entry of ucas has useCaseId '%s'"
                        .formatted(matcher.group(), matcher.group(1))));
    }

    private static void checkDeclarations(Declarations declarations, List<String> violations) {
        if (declarations == null) {
            violations.add("declarations: required");
            return;
        }
        requireTrue("declarations.informationAccurate", declarations.informationAccurate(), violations);
        requireTrue("declarations.authorisedToAct", declarations.authorisedToAct(), violations);
        requireTrue("declarations.evidenceMayBeRequested", declarations.evidenceMayBeRequested(), violations);
    }

    private void checkDocuments(List<String> violations) {
        if (document(GTC_DOCUMENT).isEmpty()) {
            violations.add(GTC_DOCUMENT + ": required");
        }
        var seen = new HashSet<String>();
        long total = 0;
        for (var document : documents) {
            var part = document.part();
            if (!seen.add(part)) {
                violations.add(part + ": sent more than once");
            }
            if (!part.equals(GTC_DOCUMENT) && !part.equals(REGISTRATION_EXTRACT_DOCUMENT)
                    && !part.equals(POWER_OF_ATTORNEY_DOCUMENT) && !UCA_DOCUMENT.matcher(part).matches()) {
                violations.add(part + ": not a document this request takes");
            }
            if (!ACCEPTED_CONTENT_TYPES.contains(normalized(document.contentType()))) {
                violations.add("%s: content type '%s' is not one of PDF, PNG or JPEG".formatted(part, document.contentType()));
            }
            if (document.size() == 0) {
                violations.add(part + ": empty");
            } else if (document.size() > MAX_DOCUMENT_BYTES) {
                violations.add(part + ": larger than 10 MB");
            }
            total += document.size();
        }
        if (total > MAX_SUBMISSION_BYTES) {
            violations.add("documents: larger than 25 MB in total");
        }
    }

    /** The bare media type: no parameters, lower case. */
    private static String normalized(String contentType) {
        if (contentType == null) {
            return "";
        }
        var semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static void requireText(String path, String value, List<String> violations) {
        if (blank(value)) {
            violations.add(path + ": required");
        }
    }

    private static void requireTrue(String path, Boolean value, List<String> violations) {
        if (!Boolean.TRUE.equals(value)) {
            violations.add(path + ": must be affirmed");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
