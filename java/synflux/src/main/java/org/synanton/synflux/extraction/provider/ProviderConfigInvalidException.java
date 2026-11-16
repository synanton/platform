package org.synanton.synflux.extraction.provider;

/** A tenant/deployment provider identifier is not in the supported vocabulary. */
public final class ProviderConfigInvalidException extends IllegalStateException {
    public ProviderConfigInvalidException(String message) {
        super(message);
    }
}
