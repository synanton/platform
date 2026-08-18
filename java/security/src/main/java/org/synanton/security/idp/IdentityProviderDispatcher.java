package org.synanton.security.idp;

import org.synanton.common.error.AuthException;

import java.util.List;

public class IdentityProviderDispatcher {

    private final List<IdentityProviderPort> providers;

    public IdentityProviderDispatcher(List<IdentityProviderPort> providers) {
        this.providers = providers;
    }

    public ValidatedIdentity resolve(String authorizationHeader) throws AuthException {
        for (IdentityProviderPort provider : providers) {
            if (provider.supports(authorizationHeader)) {
                return provider.resolve(authorizationHeader);
            }
        }
        throw new AuthException("No identity provider supports the given authorization header");
    }
}
