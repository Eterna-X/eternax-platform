package com.eternax.recon.platform.context;

import com.eternax.recon.exception.ErrorCategory;
import com.eternax.recon.exception.ErrorCode;
import com.eternax.recon.exception.EternaSyncException;

/** The identity headers were not signed by the gateway: the caller bypassed it (SEC-11). */
public final class UntrustedCallerException extends EternaSyncException {
    private static final long serialVersionUID = 1L;

    public UntrustedCallerException() {
        super(
                ErrorCode.REQ_7501_MISSING_CONTEXT,
                ErrorCategory.FORBIDDEN,
                "Request was not routed through the API gateway.");
    }
}
