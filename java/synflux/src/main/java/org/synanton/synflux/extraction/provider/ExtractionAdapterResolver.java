package org.synanton.synflux.extraction.provider;

/** Resolves the extraction adapter for the current document's tenant. */
public interface ExtractionAdapterResolver {
    ResolvedExtractionAdapter forTenant(String tenantId);
}
