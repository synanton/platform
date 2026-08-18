package org.synanton.llm;

/**
 * Strategy interface for translating provider-neutral {@link LlmClient} calls
 * to a specific backend wire format (OpenAI-compat, Anthropic direct, Bedrock, …).
 */
interface LlmProviderTranslator {

    /** Build the HTTP request body for a completion call. */
    String buildCompletionPayload(CompletionRequest request);

    /** Parse the HTTP response body from a completion call. */
    CompletionResponse parseCompletionResponse(String json);

    /** Build the HTTP request body for an embed call. */
    String buildEmbedPayload(EmbedRequest request);

    /** Parse the HTTP response body from an embed call. */
    EmbedResponse parseEmbedResponse(String json);

    /** Returns the base path suffix for completions (e.g. {@code /chat/completions}). */
    String completionPath();

    /** Returns the base path suffix for embeddings (e.g. {@code /embeddings}). */
    String embeddingPath();
}
