package org.synanton.synflux.extraction.provider;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.synflux.config.ExtractionEngineProperties;

/**
 * Tenant override -> deployment default -> hardcoded regex. Unknown identifiers
 * fail closed; there is no silent fallback to another provider.
 */
@Component
public final class ExtractionAdapterFactory implements ExtractionAdapterResolver {
    private final ProviderConfigRepository config;
    private final ExtractionEngineProperties properties;
    private final Map<ExtractionProvider, ExtractionEngineAdapter> adapters = new ConcurrentHashMap<>();

    @Autowired
    public ExtractionAdapterFactory(ProviderConfigRepository config,
                                    ExtractionEngineProperties properties) {
        this.config = config;
        this.properties = properties;
    }

    @Override
    public ResolvedExtractionAdapter forTenant(String tenantId) {
        Optional<String> override = config.findByTenant(tenantId);
        ExtractionProvider provider;
        String source;
        if (override.isPresent()) {
            provider = ExtractionProvider.parse(override.get());
            source = "tenant-override";
        } else if (properties.provider() != null && !properties.provider().isBlank()) {
            provider = ExtractionProvider.parse(properties.provider());
            source = "env-default";
        } else {
            provider = ExtractionProvider.REGEX;
            source = "hardcoded-default";
        }
        return resolve(provider, source);
    }

    @Override
    public ResolvedExtractionAdapter forProvider(String provider) {
        return resolve(ExtractionProvider.parse(provider), "persisted");
    }

    private ResolvedExtractionAdapter resolve(ExtractionProvider provider, String source) {
        ExtractionEngineAdapter adapter = adapters.computeIfAbsent(
                provider, p -> ExtractionEngines.create(p, properties));
        return new ResolvedExtractionAdapter(adapter, provider, source);
    }

    /** Test/back-compat resolver for a single pre-built adapter. */
    public static ExtractionAdapterResolver fixed(ExtractionEngineAdapter adapter) {
        ExtractionProvider provider = adapter instanceof org.synanton.extraction.engine.llm.LlmExtractionAdapter
                ? ExtractionProvider.LLM : ExtractionProvider.REGEX;
        ResolvedExtractionAdapter resolved = new ResolvedExtractionAdapter(
                adapter, provider, "fixed");
        return new ExtractionAdapterResolver() {
            @Override
            public ResolvedExtractionAdapter forTenant(String tenantId) {
                return resolved;
            }

            @Override
            public ResolvedExtractionAdapter forProvider(String persistedProvider) {
                return resolved;
            }
        };
    }
}
