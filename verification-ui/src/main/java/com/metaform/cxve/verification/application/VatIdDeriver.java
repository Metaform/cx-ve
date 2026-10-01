package com.metaform.cxve.verification.application;

/**
 * Deterministic VAT-id derivation from a unique seed (the participant's short name) — the exact
 * formula of the e2e suite's, so identities line up with what operators know from e2e runs. The
 * registration side rejects duplicates of ACTIVE unique ids, which is what makes a
 * derived-but-stable value workable.
 *
 * <p>The BPN is deliberately NOT derived here any more. It is mandatory on the run request and
 * adjudicated by the issuing side, so nothing in this environment may substitute one. The VAT id
 * has no such authority behind it yet, which is the only reason this placeholder survives.
 */
public final class VatIdDeriver {

    private VatIdDeriver() {
    }

    public static String vatIdFor(String seed) {
        return "DE" + String.format("%08X", Math.abs(seed.hashCode()));
    }
}
