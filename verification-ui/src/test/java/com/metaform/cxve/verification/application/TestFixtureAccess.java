package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.config.VerificationProperties;
import java.util.Map;

/** Opens {@link TestFixtures} (package-private) to tests in other packages. */
public final class TestFixtureAccess {

    public static final String DATASPACE = TestFixtures.DATASPACE;
    public static final String DSP_PROFILE = TestFixtures.DSP_PROFILE;
    public static final VerificationProperties.CcmApiVocabulary CCM_API = TestFixtures.CCM_API;

    private TestFixtureAccess() {
    }

    public static VerificationProperties props(Map<String, Integer> expectedEvents) {
        return TestFixtures.props(expectedEvents);
    }
}
