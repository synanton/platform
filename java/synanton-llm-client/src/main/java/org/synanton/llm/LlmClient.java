package org.synanton.llm;

public interface LlmClient {
    CompletionResponse complete(CompletionRequest request);
    EmbedResponse embed(EmbedRequest request);
}
