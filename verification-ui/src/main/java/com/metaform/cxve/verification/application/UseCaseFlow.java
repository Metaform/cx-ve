package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.util.List;

/**
 * The verification of one use case (e.g. {@code ccm}), in whatever dataspace the run is of: the
 * steps it takes and how it takes them. A use case a dataspace lists but no implementation exists
 * for is shown, but cannot be run.
 */
public interface UseCaseFlow {

    /** The use case id — the key under a dataspace profile's {@code use-cases}. */
    String useCase();

    /**
     * The run's step sequence: for a participant this environment onboards and drives itself, or
     * for a third-party system ({@code externallyHosted}) whose half of the exchange it only waits
     * for.
     */
    List<RunStep> steps(boolean externallyHosted);

    /** Runs to completion on the calling thread, recording every step on the run. Never throws. */
    void execute(VerificationRun run);
}
