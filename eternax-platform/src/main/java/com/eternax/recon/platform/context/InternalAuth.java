package com.eternax.recon.platform.context;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proves that identity headers were written by the API gateway (or another trusted service) and not
 * by someone who reached a service port directly (SEC-11). The signature covers tenant, user, roles
 * and a timestamp, so a captured set of headers cannot be re-used for another tenant and expires
 * after {@link #MAX_SKEW_SECONDS}.
 *
 * <p>With no secret configured verification is off, which is only acceptable on a developer
 * machine; the filter logs a warning at start-up.
 */
public final class InternalAuth {

    public static final String TIMESTAMP_HEADER = "X-Gateway-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Gateway-Signature";
    public static final long MAX_SKEW_SECONDS = 300;

    private final byte[] secret;
    private final Clock clock;

    public InternalAuth(String secret, Clock clock) {
        this.secret =
                secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public boolean enabled() {
        return secret != null;
    }

    /** Identity headers plus signature for an outgoing service-to-service call. */
    public Map<String, String> headersFor(String tenant, String user, Set<String> roles) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestContext.TENANT_HEADER, tenant);
        h.put(RequestContext.USER_HEADER, user);
        h.put(RequestContext.ROLES_HEADER, String.join(",", new TreeSet<>(roles)));
        if (enabled()) {
            String ts = Long.toString(clock.instant().getEpochSecond());
            h.put(TIMESTAMP_HEADER, ts);
            h.put(SIGNATURE_HEADER, sign(tenant, user, h.get(RequestContext.ROLES_HEADER), ts));
        }
        return h;
    }

    /** True when verification is off or the request carries a valid, fresh signature. */
    public boolean verify(HttpServletRequest request) {
        if (!enabled()) {
            return true;
        }
        String ts = request.getHeader(TIMESTAMP_HEADER);
        String sig = request.getHeader(SIGNATURE_HEADER);
        if (ts == null || sig == null) {
            return false;
        }
        long issued;
        try {
            issued = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - issued) > MAX_SKEW_SECONDS) {
            return false;
        }
        String roles = request.getHeader(RequestContext.ROLES_HEADER);
        String expected =
                sign(
                        request.getHeader(RequestContext.TENANT_HEADER),
                        request.getHeader(RequestContext.USER_HEADER),
                        roles == null ? "" : roles,
                        ts);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    /** Signature for the given identity; used by the gateway, which has no servlet request. */
    public String sign(String tenant, String user, String roles, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String payload = tenant + "\n" + user + "\n" + roles + "\n" + timestamp;
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
