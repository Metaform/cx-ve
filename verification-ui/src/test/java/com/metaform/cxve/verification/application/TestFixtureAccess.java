package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.config.VerificationProperties;
import java.util.Map;

/** Opens {@link TestFixtures} (package-private) to tests in other packages. */
public final class TestFixtureAccess {

    private TestFixtureAccess() {
    }

    public static VerificationProperties props(Map<String, Integer> expectedEvents) {
        return TestFixtures.props(expectedEvents);
    }
}
