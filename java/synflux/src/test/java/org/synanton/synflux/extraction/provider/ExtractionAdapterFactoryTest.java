package org.synanton.synflux.extraction.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.llm.LlmExtractionAdapter;
import org.synanton.extraction.engine.regex.RegexExtractorAdapter;
import org.synanton.synflux.config.ExtractionEngineProperties;

class ExtractionAdapterFactoryTest {

    private static ExtractionEngineProperties props(String provider) {
        return new ExtractionEngineProperties(provider, "http://localhost:8000/v1",
                "qwen3-extraction", 120, null, null, false);
    }

    private static ExtractionAdapterFactory factory(String defaultProvider,
                                                    Map<String, String> overrides) {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        when(repo.findByTenant(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        overrides.get(invocation.getArgument(0, String.class))));
        return new ExtractionAdapterFactory(repo, props(defaultProvider));
    }

    @Test
    void tenantOverrideWinsAndVllmAliasesToLlm() {
        ExtractionAdapterFactory factory = factory("regex",
                Map.of("eval-llm", "vllm", "eval-regex", "regex"));

        ResolvedExtractionAdapter llm = factory.forTenant("eval-llm");
        assertThat(llm.adapter()).isInstanceOf(LlmExtractionAdapter.class);
        assertThat(llm.provider()).isEqualTo(ExtractionProvider.LLM);
        assertThat(llm.provider().canonical()).isEqualTo("llm");
        assertThat(llm.providerSource()).isEqualTo("tenant-override");

        ResolvedExtractionAdapter regex = factory.forTenant("eval-regex");
        assertThat(regex.adapter()).isInstanceOf(RegexExtractorAdapter.class);
        assertThat(regex.provider()).isEqualTo(ExtractionProvider.REGEX);
        assertThat(regex.providerSource()).isEqualTo("tenant-override");
    }

    @Test
    void demoAndUnconfiguredTenantsUseEnvDefault() {
        ExtractionAdapterFactory factory = factory("regex", Map.of());

        assertThat(factory.forTenant("demo").adapter()).isInstanceOf(RegexExtractorAdapter.class);
        assertThat(factory.forTenant("demo").providerSource()).isEqualTo("env-default");
        assertThat(factory.forTenant("other").adapter()).isInstanceOf(RegexExtractorAdapter.class);
        assertThat(factory.forTenant("other").providerSource()).isEqualTo("env-default");
    }

    @Test
    void invalidTenantOverrideFailsClosed() {
        ExtractionAdapterFactory factory = factory("regex", Map.of("eval-bad", "garbage"));

        assertThatThrownBy(() -> factory.forTenant("eval-bad"))
                .isInstanceOf(ProviderConfigInvalidException.class)
                .hasMessageContaining("garbage");
    }

    @Test
    void invalidEnvDefaultFailsClosed() {
        ExtractionAdapterFactory factory = factory("Llm", Map.of());

        assertThatThrownBy(() -> factory.forTenant("demo"))
                .isInstanceOf(ProviderConfigInvalidException.class)
                .hasMessageContaining("Llm");
    }

    @Test
    void blankEnvDefaultUsesHardcodedRegex() {
        ExtractionAdapterFactory factory = factory(null, Map.of());

        ResolvedExtractionAdapter resolved = factory.forTenant("demo");
        assertThat(resolved.adapter()).isInstanceOf(RegexExtractorAdapter.class);
        assertThat(resolved.providerSource()).isEqualTo("hardcoded-default");
    }

    @Test
    void validOverrideWinsOverInvalidEnvDefault() {
        ExtractionAdapterFactory factory = factory("Llm", Map.of("eval-llm", "llm"));

        assertThat(factory.forTenant("eval-llm").adapter()).isInstanceOf(LlmExtractionAdapter.class);
        assertThat(factory.forTenant("eval-llm").providerSource()).isEqualTo("tenant-override");
    }

    @Test
    void configDeletionRevertsToEnvDefault() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        when(repo.findByTenant("eval-llm"))
                .thenReturn(Optional.of("llm"))
                .thenReturn(Optional.empty());
        ExtractionAdapterFactory factory = new ExtractionAdapterFactory(repo, props("regex"));

        assertThat(factory.forTenant("eval-llm").adapter()).isInstanceOf(LlmExtractionAdapter.class);
        ResolvedExtractionAdapter afterDelete = factory.forTenant("eval-llm");
        assertThat(afterDelete.adapter()).isInstanceOf(RegexExtractorAdapter.class);
        assertThat(afterDelete.providerSource()).isEqualTo("env-default");
    }

    @Test
    void concurrentLookupsResolvePerTenantWithoutLeakage() throws Exception {
        ExtractionAdapterFactory factory = factory("regex",
                Map.of("tenanta", "regex", "tenantb", "llm", "tenantc", "vllm"));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<String>> tasks = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(i -> (Callable<String>) () -> {
                        String tenant = switch (i % 3) {
                            case 0 -> "tenanta";
                            case 1 -> "tenantb";
                            default -> "tenantc";
                        };
                        ResolvedExtractionAdapter r = factory.forTenant(tenant);
                        return tenant + "=" + r.provider().canonical();
                    })
                    .toList();
            for (Future<String> f : pool.invokeAll(tasks)) {
                String result = f.get();
                if (result.startsWith("tenanta=")) {
                    assertThat(result).isEqualTo("tenanta=regex");
                } else {
                    assertThat(result).isEqualTo(
                            result.substring(0, result.indexOf('=')) + "=llm");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
