package com.metaform.cxve.verification.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An external participant's member id: declared up front, or adopted once the dataspace assigned it. */
class VerificationRunTest {

    private static VerificationRun run(String memberId) {
        return new VerificationRun("r1", "decade-x", "ccm", "SUT GmbH", "sut", memberId, "DE0001",
                "did:web:sut.example.com", RunStep.EXTERNAL);
    }

    @Test
    void aRunWithoutAMemberId_adoptsTheOneTheDataspaceAssigned() {
        var run = run(null);

        run.onExternallyOnboarded("did:web:sut.example.com", "process-1", "DX-00000042");

        assertThat(run.memberId()).isEqualTo("DX-00000042");
        assertThat(run.summary().memberId()).isEqualTo("DX-00000042");
    }

    @Test
    void aDeclaredMemberId_isKept() {
        var run = run("DX-00000001");

        run.onExternallyOnboarded("did:web:sut.example.com", "process-1", "DX-00000042");

        assertThat(run.memberId()).isEqualTo("DX-00000001");
    }
}
