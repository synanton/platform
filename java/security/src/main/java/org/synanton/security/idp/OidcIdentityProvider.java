package org.synanton.security.idp;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.synanton.common.error.AuthException;

import java.util.List;

/**
 * Accepts bearer JWTs whose {@code iss} matches the configured OIDC issuer.
 * Signature verification against JWKS is performed when {@link JwksVerifier} is supplied;
 * otherwise the token is parsed for claims after a format check (dev/test).
 */
public class OidcIdentityProvider implements IdentityProviderPort {

    public interface JwksVerifier {
        JWTClaimsSet verify(String token) throws Exception;
    }

    private final String issuerUri;
    private final JwksVerifier verifier;

    public OidcIdentityProvider(String issuerUri, JwksVerifier verifier) {
        this.issuerUri = issuerUri;
        this.verifier = verifier;
    }

    @Override
    public boolean supports(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer eyJ")) {
            return false;
        }
        try {
            String token = authorizationHeader.substring("Bearer ".length());
            SignedJWT jwt = SignedJWT.parse(token);
            String issuer = jwt.getJWTClaimsSet().getIssuer();
            return issuerUri != null && issuerUri.equals(issuer);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ValidatedIdentity resolve(String authorizationHeader) throws AuthException {
        String token = authorizationHeader.substring("Bearer ".length());
        try {
            JWTClaimsSet claims = verifier != null
                    ? verifier.verify(token)
                    : SignedJWT.parse(token).getJWTClaimsSet();
            if (issuerUri != null && !issuerUri.equals(claims.getIssuer())) {
                throw new AuthException("OIDC issuer mismatch");
            }
            String subject = claims.getSubject();
            String tenantId = claims.getStringClaim("tenant_id");
            if (tenantId == null) {
                tenantId = "demo";
            }
            List<String> roles = claims.getStringListClaim("roles");
            String[] scopes = roles == null ? new String[0] : roles.toArray(String[]::new);
            return new ValidatedIdentity(subject, tenantId, "USER_SUBJECT", scopes, null);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("OIDC token invalid: " + e.getMessage());
        }
    }
}
