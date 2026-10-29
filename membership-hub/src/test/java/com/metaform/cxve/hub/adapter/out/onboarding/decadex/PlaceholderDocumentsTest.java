package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The placeholders are well-formed PDFs that say what they stand in for. */
class PlaceholderDocumentsTest {

    @Test
    void aPlaceholderIsAPdfNamingTheDocument() {
        var pdf = new String(PlaceholderDocuments.uca("export-control", "v1.0", "Acme (Europe) Corp"),
                StandardCharsets.ISO_8859_1);

        assertThat(pdf).startsWith("%PDF-1.4\n").endsWith("%%EOF\n");
        assertThat(pdf).contains("PLACEHOLDER", "Use Case Agreement 'export-control', version v1.0",
                "Accepted by Acme \\(Europe\\) Corp");
    }

    @Test
    void theXrefPointsAtTheObjects() {
        var pdf = new String(PlaceholderDocuments.gtc("1.0", "Acme Corp"), StandardCharsets.ISO_8859_1);
        var startxref = Integer.parseInt(pdf.substring(pdf.indexOf("startxref\n") + 10, pdf.indexOf("\n%%EOF")).trim());

        assertThat(pdf.substring(startxref)).startsWith("xref\n0 6\n");
        var firstOffset = Integer.parseInt(pdf.substring(startxref).split("\n")[3].substring(0, 10));
        assertThat(pdf.substring(firstOffset)).startsWith("1 0 obj\n<< /Type /Catalog");
    }
}
