package org.synanton.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the correct {@link LlmClient} implementation based on the
 * {@code llm.provider} configuration value.
 *
 * <p>Supported values:
 * <ul>
 *   <li>{@code openai-compat} (default) - OpenAI-compatible REST API</li>
 *   <li>{@code anthropic-direct} - Anthropic Messages API</li>
 *   <li>{@code bedrock} - stub, throws {@link UnsupportedOperationException} in Phase 3</li>
 * </ul>
 */
public final class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    private LlmClientFactory() {}

    /**
     * @param provider   one of {@code openai-compat}, {@code anthropic-direct}, {@code bedrock}
     * @param baseUrl    provider base URL (Anthropic: https://api.anthropic.com/v1)
     * @param maxRetries maximum retry attempts on retryable errors
     */
    public static LlmClient create(String provider, String baseUrl, int maxRetries) {
        return switch (provider == null ? "openai-compat" : provider) {
            case "openai-compat" -> {
                log.info("LlmClientFactory: using OpenAI-compat translator at {}", baseUrl);
                yield new HttpLlmClient(baseUrl, maxRetries, new OpenAiCompatTranslator());
            }
            case "anthropic-direct" -> {
                log.info("LlmClientFactory: using Anthropic direct translator at {}", baseUrl);
                yield new AnthropicHttpLlmClient(baseUrl, maxRetries);
            }
            case "bedrock" -> throw new UnsupportedOperationException(
                    "Bedrock translator is not implemented in Phase 3. Use openai-compat or anthropic-direct.");
            default -> throw new IllegalArgumentException("Unknown llm.provider: " + provider);
        };
    }
}
