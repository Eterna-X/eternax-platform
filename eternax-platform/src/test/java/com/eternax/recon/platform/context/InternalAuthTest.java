package com.eternax.recon.platform.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class InternalAuthTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Clock now = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);
    private final InternalAuth auth = new InternalAuth(SECRET, now);

    private static MockHttpServletRequest request(Map<String, String> headers) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/cases");
        headers.forEach(r::addHeader);
        return r;
    }

    @Test
    void aSignedRequestIsAccepted() {
        var headers = auth.headersFor("bank", "alice", Set.of("ADMIN", "APPROVER"));
        assertThat(auth.verify(request(headers))).isTrue();
    }

    @Test
    void headersWithoutSignatureAreRefused() {
        var r =
                request(
                        Map.of(
                                "X-Tenant-Id", "bank",
                                "X-User-Id", "alice",
                                "X-User-Roles", "ADMIN"));
        assertThat(auth.verify(r)).isFalse();
    }

    @Test
    void changingTheTenantInvalidatesTheSignature() {
        var headers = new HashMap<>(auth.headersFor("bank", "alice", Set.of("ADMIN")));
        headers.put("X-Tenant-Id", "victim");
        assertThat(auth.verify(request(headers))).isFalse();
    }

    @Test
    void changingTheRolesInvalidatesTheSignature() {
        var headers = new HashMap<>(auth.headersFor("bank", "alice", Set.of("READ_ONLY")));
        headers.put("X-User-Roles", "ADMIN");
        assertThat(auth.verify(request(headers))).isFalse();
    }

    @Test
    void anOldSignatureExpires() {
        var headers = auth.headersFor("bank", "alice", Set.of("ADMIN"));
        var later = Clock.fixed(Instant.parse("2026-10-13T10:10:00Z"), ZoneOffset.UTC);
        assertThat(new InternalAuth(SECRET, later).verify(request(headers))).isFalse();
    }

    @Test
    void aDifferentSecretIsRefused() {
        var headers =
                new InternalAuth("another-secret-another-secret-12345", now)
                        .headersFor("bank", "alice", Set.of("ADMIN"));
        assertThat(auth.verify(request(headers))).isFalse();
    }

    @Test
    void withNoSecretVerificationIsOffAndNothingIsSigned() {
        var open = new InternalAuth("", now);
        assertThat(open.headersFor("bank", "alice", Set.of("ADMIN")))
                .doesNotContainKey("X-Gateway-Signature");
        assertThat(open.verify(request(Map.of()))).isTrue();
    }
}
