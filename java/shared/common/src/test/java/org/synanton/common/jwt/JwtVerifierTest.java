package org.synanton.common.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.error.AuthException;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {

    private static final String SECRET = "synanton-test-secret-key-32bytes!!";
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new JwtVerifier(SECRET);
    }

    @Test
    void roundTrip_valid() throws Exception {
        String token = buildToken(SECRET, "alice", "demo", 1001, List.of(1001, 2000), 3600);
        SubjectAssertion result = verifier.verify(token);

        assertThat(result.subject()).isEqualTo("alice");
        assertThat(result.tenantId()).isEqualTo("demo");
        assertThat(result.uid()).isEqualTo(1001);
        assertThat(result.gids()).containsExactly(1001, 2000);
    }

    @Test
    void expired_throws() throws Exception {
        String token = buildToken(SECRET, "alice", "demo", 1001, List.of(1001), -1);
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void wrongSecret_throws() throws Exception {
        String token = buildToken("wrong-secret-key-at-least-32bytes!!", "alice", "demo", 1001, List.of(1001), 3600);
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void malformed_throws() {
        assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
                .isInstanceOf(AuthException.class);
    }

    private static String buildToken(
            String secret, String subject, String tenantId,
            int uid, List<Integer> gids, long expiresInSeconds
    ) throws Exception {
        JWSSigner signer = new MACSigner(secret.getBytes());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("tenant_id", tenantId)
                .claim("uid", uid)
                .claim("gid", gids)
                .expirationTime(Date.from(Instant.now().plusSeconds(expiresInSeconds)))
                .issueTime(new Date())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }
}
