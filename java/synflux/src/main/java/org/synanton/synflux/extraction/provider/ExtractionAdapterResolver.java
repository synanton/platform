package org.synanton.synflux.extraction.provider;

/** Resolves the extraction adapter for the current document's tenant. */
public interface ExtractionAdapterResolver {
    ResolvedExtractionAdapter forTenant(String tenantId);

    /** Resolve a provider already persisted on the source version (retry path). */
    ResolvedExtractionAdapter forProvider(String provider);
}
