package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import java.util.regex.Pattern;

/**
 * The version a change is made on, from its {@code If-Match}: exactly one strong ETag as the API
 * returns it, e.g. {@code "3"}. A weak one ({@code W/"3"}), {@code *} or a list is not a version.
 */
final class IfMatch {

    static final String HEADER = "If-Match";

    private static final Pattern VERSION = Pattern.compile("\"(\\d{1,18})\"");

    private IfMatch() {
    }

    /**
     * @throws ProblemException {@code precondition-required} without one, {@code malformed-if-match}
     *                          for anything but a version
     */
    static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ProblemException(ProblemCode.PRECONDITION_REQUIRED,
                    "If-Match is missing: send the request's current version (its ETag), e.g. \"3\"");
        }
        var matcher = VERSION.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new ProblemException(ProblemCode.MALFORMED_IF_MATCH,
                    "If-Match '%s' is not a version: send the ETag as it was returned, e.g. \"3\"".formatted(ifMatch));
        }
        return Long.parseLong(matcher.group(1));
    }

    /** The ETag of a version. */
    static String etag(long version) {
        return "\"" + version + "\"";
    }
}
