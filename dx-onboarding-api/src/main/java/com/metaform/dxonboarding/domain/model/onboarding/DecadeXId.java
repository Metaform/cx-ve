package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.concurrent.ThreadLocalRandom;

/** A Decade-X-ID: Decade-X's id of a member — {@code DX-} and 8 digits. */
public final class DecadeXId {

    public static final String PATTERN = "DX-[0-9]{8}";

    private DecadeXId() {
    }

    /** A random Decade-X-ID; the caller makes sure it is not taken. */
    public static String random() {
        return "DX-%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
