package com.eternax.recon.platform.context;

import com.eternax.recon.exception.MissingRequestContextException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Builds the {@link RequestContext} from gateway headers, puts tenant/user/correlation id on the
 * logging MDC, and requires tenant and user identity for every {@code /api/} call. Failures are
 * routed to the global exception handler so clients get the standard error body.
 */
public final class RequestContextFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver exceptionResolver;

    public RequestContextFilter(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(RequestContext.CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        response.setHeader(RequestContext.CORRELATION_HEADER, correlationId);
        MDC.put("correlationId", correlationId);
        try {
            String tenant = require(request, RequestContext.TENANT_HEADER);
            String user = require(request, RequestContext.USER_HEADER);
            String rolesHeader = request.getHeader(RequestContext.ROLES_HEADER);
            Set<String> roles =
                    rolesHeader == null || rolesHeader.isBlank()
                            ? Set.of()
                            : Arrays.stream(rolesHeader.split(","))
                                    .map(String::trim)
                                    .filter(s -> !s.isEmpty())
                                    .collect(Collectors.toUnmodifiableSet());
            RequestContextHolder.set(new RequestContext(tenant, user, roles, correlationId));
            MDC.put("tenantId", tenant);
            MDC.put("userId", user);
            chain.doFilter(request, response);
        } catch (MissingRequestContextException e) {
            exceptionResolver.resolveException(request, response, null, e);
        } finally {
            RequestContextHolder.clear();
            MDC.clear();
        }
    }

    private static String require(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            throw new MissingRequestContextException(header);
        }
        return value;
    }
}
