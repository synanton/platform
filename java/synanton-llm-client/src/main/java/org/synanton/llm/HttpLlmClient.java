package org.synanton.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmClient.class);

    private final String baseUrl;
    private final int maxRetries;
    private final HttpClient httpClient;
    private final LlmProviderTranslator translator;

    public HttpLlmClient(String baseUrl, int maxRetries) {
        this(baseUrl, maxRetries, new OpenAiCompatTranslator());
    }

    public HttpLlmClient(String baseUrl, int maxRetries, LlmProviderTranslator translator) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxRetries = maxRetries;
        this.translator = translator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        String payload = translator.buildCompletionPayload(request);
        String responseBody = post(baseUrl + translator.completionPath(), payload);
        return translator.parseCompletionResponse(responseBody);
    }

    @Override
    public EmbedResponse embed(EmbedRequest request) {
        int inputChars = request.inputs().stream().mapToInt(String::length).sum();
        long start = System.nanoTime();
        String payload = translator.buildEmbedPayload(request);
        String responseBody = post(baseUrl + translator.embeddingPath(), payload);
        EmbedResponse parsed = translator.parseEmbedResponse(responseBody, inputChars);
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        if (parsed.durationMs() == 0) {
            return new EmbedResponse(
                parsed.embeddings(), parsed.inputChars(), parsed.outputChars(),
                durationMs, parsed.inputTokens(), parsed.outputTokens());
        }
        return parsed;
    }

    private String post(String url, String body) {
        int attempt = 0;
        long backoffMs = 500;
        while (true) {
            attempt++;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();

                if (status == 200) return resp.body();

                boolean retryable = (status == 429 || status >= 500);
                if (!retryable || attempt > maxRetries) {
                    throw new LlmClientException("HTTP " + status + ": " + resp.body());
                }

                // Honor Retry-After header when present, otherwise use exponential backoff
                long waitMs = resp.headers().firstValue("Retry-After")
                        .map(v -> Long.parseLong(v) * 1000L)
                        .orElse(backoffMs);
                log.warn("LLM call failed with {} (attempt {}), retrying in {}ms", status, attempt, waitMs);
                Thread.sleep(waitMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);

            } catch (LlmClientException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmClientException("Interrupted during retry");
            } catch (IOException e) {
                if (attempt > maxRetries) throw new LlmClientException("IO error after " + attempt + " attempts: " + e.getMessage());
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                backoffMs = Math.min(backoffMs * 2, 30_000);
                log.warn("IO error calling LLM (attempt {}): {}", attempt, e.getMessage());
            }
        }
    }
}
