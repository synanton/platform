package org.synanton.synflux.extraction.provider;

import org.synanton.extraction.engine.ExtractionEngineAdapter;

/** Adapter plus the audit attribution recorded with the extraction run. */
public record ResolvedExtractionAdapter(
        ExtractionEngineAdapter adapter,
        ExtractionProvider provider,
        String providerSource) {
}
