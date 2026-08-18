package org.synanton.security.idp;

import org.synanton.common.error.AuthException;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.jwt.SubjectAssertion;

public class JwtIdentityProvider implements IdentityProviderPort {

    private final JwtVerifier jwtVerifier;

    public JwtIdentityProvider(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    public boolean supports(String authorizationHeader) {
        if (authorizationHeader == null) return false;
        if (!authorizationHeader.startsWith("Bearer ")) return false;
        String token = authorizationHeader.substring(7);
        return !token.startsWith("syn_");
    }

    @Override
    public ValidatedIdentity resolve(String authorizationHeader) throws AuthException {
        String token = authorizationHeader.substring(7);
        SubjectAssertion assertion = jwtVerifier.verify(token);
        return new ValidatedIdentity(
                assertion.subject(),
                assertion.tenantId(),
                "JWT",
                new String[0],
                null
        );
    }
}
