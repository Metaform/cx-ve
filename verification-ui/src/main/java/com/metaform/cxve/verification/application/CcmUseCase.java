package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Company certificate management: a certificate exchange through Certo, whichever dataspace's CCM
 * standard it follows — the dataspace profile supplies what differs. A managed participant is
 * driven end to end ({@link CertificateExchangeFlow}); a third-party one only on this environment's
 * side ({@link ExternalCertificateExchangeFlow}).
 */
@Component
public class CcmUseCase implements UseCaseFlow {

    public static final String ID = "ccm";

    private final CertificateExchangeFlow managedFlow;
    private final ExternalCertificateExchangeFlow externalFlow;

    public CcmUseCase(CertificateExchangeFlow managedFlow, ExternalCertificateExchangeFlow externalFlow) {
        this.managedFlow = managedFlow;
        this.externalFlow = externalFlow;
    }

    @Override
    public String useCase() {
        return ID;
    }

    @Override
    public List<RunStep> steps(boolean externallyHosted) {
        return externallyHosted ? RunStep.EXTERNAL : RunStep.MANAGED;
    }

    @Override
    public void execute(VerificationRun run) {
        if (run.externallyHosted()) {
            externalFlow.execute(run);
        } else {
            managedFlow.execute(run);
        }
    }
}
