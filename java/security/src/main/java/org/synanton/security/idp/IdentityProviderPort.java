package org.synanton.security.idp;

import org.synanton.common.error.AuthException;

public interface IdentityProviderPort {
    boolean supports(String authorizationHeader);
    ValidatedIdentity resolve(String authorizationHeader) throws AuthException;
}
