package com.eternax.recon.platform.context;

import com.eternax.recon.exception.MissingRequestContextException;
import java.util.Optional;

/** Thread-bound request identity; always cleared by {@link RequestContextFilter}. */
public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {}

    static void set(RequestContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Optional<RequestContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static RequestContext require() {
        RequestContext context = CURRENT.get();
        if (context == null) {
            throw new MissingRequestContextException(RequestContext.TENANT_HEADER);
        }
        return context;
    }
}
