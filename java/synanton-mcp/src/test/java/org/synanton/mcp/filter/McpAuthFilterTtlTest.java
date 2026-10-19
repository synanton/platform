package org.synanton.mcp.filter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #242: the identity cache must not outlive the credential. The TTL is the
 * configured bound capped by the token's remaining lifetime — the missing
 * test from #241's review, now possible because the wire carries exp.
 */
class McpAuthFilterTtlTest {

    private static final Duration CONFIGURED = Duration.ofSeconds(60);

    @Test
    void noCredentialExpiryUsesTheConfiguredBound() {
        assertThat(McpAuthFilter.effectiveTtl(CONFIGURED, null)).isEqualTo(CONFIGURED);
    }

    @Test
    void tokenOutlivingTheConfiguredBoundUsesTheConfiguredBound() {
        Instant farFuture = Instant.now().plus(Duration.ofHours(1));
        assertThat(McpAuthFilter.effectiveTtl(CONFIGURED, farFuture)).isEqualTo(CONFIGURED);
    }

    @Test
    void shortLivedTokenCapsTheCacheTtl() {
        Instant soon = Instant.now().plusSeconds(10);
        Duration ttl = McpAuthFilter.effectiveTtl(CONFIGURED, soon);
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(11));
        assertThat(ttl).isGreaterThan(Duration.ZERO);
    }

    @Test
    void expiredTokenIsNotCached() {
        assertThat(McpAuthFilter.effectiveTtl(CONFIGURED, Instant.now().minusSeconds(1)))
                .isEqualTo(Duration.ZERO);
    }
}
