package org.synanton.security.api;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.synanton.common.error.AuthException;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.security.app.SecurityProperties;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
public class TokenExchangeController {

    private static final String TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String TOKEN_TYPE_ACCESS = "urn:ietf:params:oauth:token-type:access_token";

    private final JwtVerifier jwtVerifier;
    private final JdbcTemplate jdbc;
    private final SecurityProperties props;

    public TokenExchangeController(JwtVerifier jwtVerifier, JdbcTemplate jdbc, SecurityProperties props) {
        this.jwtVerifier = jwtVerifier;
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostMapping(value = "/auth/token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<TokenExchangeResponse> exchange(
            @RequestParam("grant_type") String grantType,
            @RequestParam("subject_token") String subjectToken,
            @RequestParam(value = "subject_token_type", required = false) String subjectTokenType,
            @RequestParam(value = "actor_id") String actorId
    ) {
        if (!TOKEN_EXCHANGE_GRANT.equals(grantType)) {
            return ResponseEntity.badRequest().build();
        }

        SubjectAssertion assertion = jwtVerifier.verify(subjectToken);

        List<Map<String, Object>> svc = jdbc.queryForList(
                "SELECT service_name FROM security.service_accounts WHERE service_name = ?", actorId
        );
        if (svc.isEmpty()) throw new AuthException("Unknown service account: " + actorId);

        long ttl = props.tokenExchange() != null ? props.tokenExchange().serviceTokenTtlSeconds() : 300;
        String serviceToken = issueServiceJwt(actorId, assertion.tenantId(), ttl);

        return ResponseEntity.ok(new TokenExchangeResponse(
                serviceToken, TOKEN_TYPE_ACCESS, "Bearer", ttl
        ));
    }

    private String issueServiceJwt(String actorId, String tenantId, long ttlSeconds) {
        try {
            var signer = new MACSigner(props.jwt().secret().getBytes());
            var claims = new JWTClaimsSet.Builder()
                    .subject("service:" + actorId)
                    .claim("tenant_id", tenantId)
                    .claim("scope", "service")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(ttlSeconds)))
                    .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
    }

    public record TokenExchangeResponse(
            String access_token,
            String issued_token_type,
            String token_type,
            long expires_in
    ) {}
}
