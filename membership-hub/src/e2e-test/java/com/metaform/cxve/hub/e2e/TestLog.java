package com.metaform.cxve.hub.e2e;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal timestamped progress output for the long-running e2e flows. Plain stdout — the
 * e2eTest Gradle task has showStandardStreams enabled, so these lines appear live in the
 * test run.
 */
final class TestLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private TestLog() {
    }

    static void log(String format, Object... args) {
        System.out.printf("[%s] %s%n", LocalTime.now().format(TIME), format.formatted(args));
    }
}
