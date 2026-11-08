package org.synanton.synflux.extraction.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;

class GraphWriteTokenMinterTest {

    @Test
    void mintsATenantTokenRelixCanVerify() {
        String secret = "relix-test-jwt-secret-at-least-32-bytes";
        String token = new GraphWriteTokenMinter(secret).mint("tenant-a");
        var assertion = new JwtVerifier(secret).verify(token);
        assertThat(assertion.tenantId()).isEqualTo("tenant-a");
        assertThat(assertion.subject()).isEqualTo("synflux-extraction");
    }
}
