package org.synanton.security.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.synanton.common.error.AuthException;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.security.app.SecurityProperties;
import org.synanton.security.idp.HtpasswdBackend;
import org.synanton.security.idp.UserRecord;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

public class AuthService {

    private final HtpasswdBackend backend;
    private final SecurityProperties props;
    private final JwtVerifier jwtVerifier;
    private final org.synanton.security.idp.IdentityProviderDispatcher dispatcher;

    public AuthService(HtpasswdBackend backend, SecurityProperties props,
                       org.synanton.security.idp.IdentityProviderDispatcher dispatcher) {
        this.backend = backend;
        this.props = props;
        this.jwtVerifier = new JwtVerifier(props.jwt().secret());
        this.dispatcher = dispatcher;
    }

    public AuthService(HtpasswdBackend backend, SecurityProperties props) {
        this(backend, props, new org.synanton.security.idp.IdentityProviderDispatcher(
                java.util.List.of(new org.synanton.security.idp.JwtIdentityProvider(
                        new JwtVerifier(props.jwt().secret())))));
    }

    public TokenResponse login(String username, String password) {
        Optional<UserRecord> user = backend.authenticate(username, password);
        if (user.isEmpty()) {
            throw new AuthException("Invalid credentials");
        }
        UserRecord u = user.get();
        String token = issueJwt(u);
        return new TokenResponse(token, props.jwt().expiresInSeconds());
    }

    public SubjectAssertion validate(String token) {
        return jwtVerifier.verify(token);
    }

    public org.synanton.security.idp.ValidatedIdentity validateHeader(String authorizationHeader) {
        return dispatcher.resolve(authorizationHeader);
    }

    private String issueJwt(UserRecord user) {
        try {
            JWSSigner signer = new MACSigner(props.jwt().secret().getBytes());
            long expiresIn = props.jwt().expiresInSeconds();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user.username())
                    .claim("tenant_id", props.jwt().tenantId())
                    .claim("uid", user.uid())
                    .claim("gid", user.gids())
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(expiresIn)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
    }

    public record TokenResponse(String token, long expiresIn) {}
}
