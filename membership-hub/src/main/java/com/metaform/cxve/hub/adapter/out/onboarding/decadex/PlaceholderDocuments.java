package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Stand-ins for the signed documents the Decade-X TSP requires with an onboarding request — the
 * General Terms and Conditions, and one use case agreement per use case. Nobody signs anything in
 * the VE, so the hub submits a one-page PDF per document that merely says what it stands in for.
 * They are PLACEHOLDERS, not signed documents: a TSP operator reviewing the request sees exactly
 * that.
 */
final class PlaceholderDocuments {

    private PlaceholderDocuments() {
    }

    /** The GTC of the given version, as accepted by the applicant. */
    static byte[] gtc(String versionNumber, String legalName) {
        return pdf("PLACEHOLDER - not a signed document",
                "General Terms and Conditions, version " + versionNumber,
                "Accepted by " + legalName + " through the Membership Hub");
    }

    /** The use case agreement of the given use case, as accepted by the applicant. */
    static byte[] uca(String useCaseId, String versionNumber, String legalName) {
        return pdf("PLACEHOLDER - not a signed document",
                "Use Case Agreement '" + useCaseId + "'" + (versionNumber == null ? "" : ", version " + versionNumber),
                "Accepted by " + legalName + " through the Membership Hub");
    }

    /** A minimal one-page PDF showing the given lines in Helvetica. */
    static byte[] pdf(String... lines) {
        var text = new StringBuilder("BT /F1 12 Tf 72 720 Td 16 TL");
        for (var line : lines) {
            text.append(" (").append(escape(line)).append(") Tj T*");
        }
        text.append(" ET");
        var content = text.toString();
        var objects = new String[] {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R"
                        + " /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + content.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + content
                        + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        };

        var out = new ByteArrayOutputStream();
        var offsets = new ArrayList<Integer>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.length; i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n");
        }
        var xref = out.size();
        var trailer = new StringBuilder("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
        offsets.forEach(offset -> trailer.append("%010d 00000 n \n".formatted(offset)));
        trailer.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xref).append("\n%%EOF\n");
        write(out, trailer.toString());
        return out.toByteArray();
    }

    /** PDF string escaping; characters outside Latin-1 become '?'. */
    private static String escape(String line) {
        var escaped = new StringBuilder();
        for (var c : line.toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                escaped.append('\\').append(c);
            } else {
                escaped.append(c < 0x20 || c > 0xFF ? '?' : c);
            }
        }
        return escaped.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
