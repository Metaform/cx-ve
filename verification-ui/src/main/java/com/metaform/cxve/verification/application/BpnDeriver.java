package com.metaform.cxve.verification.application;

/**
 * Deterministic BPN/VAT derivation from a unique seed (the participant's short name) — the exact
 * formula of the e2e suite's {@code bpnFor}, so identities line up with what operators know from
 * e2e runs. The registration side rejects duplicates of ACTIVE registrations, which is what makes
 * a derived-but-stable value workable.
 */
public final class BpnDeriver {

    private BpnDeriver() {
    }

    public static String bpnFor(String seed) {
        return ("BPNL" + String.format("%08X", Math.abs(seed.hashCode())) + "000000").substring(0, 16);
    }

    public static String vatIdFor(String seed) {
        return "DE" + String.format("%08X", Math.abs(seed.hashCode()));
    }
}
