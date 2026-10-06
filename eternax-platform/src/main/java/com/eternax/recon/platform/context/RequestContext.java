package com.eternax.recon.platform.context;

import java.util.Set;

/** Identity of the caller, injected by the API gateway as trusted headers. */
public record RequestContext(
        String tenantId, String userId, Set<String> roles, String correlationId) {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";
    public static final String ROLES_HEADER = "X-User-Roles";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
