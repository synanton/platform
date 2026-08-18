package org.synanton.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client wired to the Anthropic Messages API.
 * Adds the {@code anthropic-version} header and handles Anthropic-specific
 * status codes: 529 Overloaded is retryable; 400 invalid_request_error is not.
 */
class AnthropicHttpLlmClient implements LlmClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String baseUrl;
    private final int maxRetries;
    private final AnthropicDirectTranslator translator = new AnthropicDirectTranslator();
    private final HttpClient httpClient;
    private final String apiKey;

    AnthropicHttpLlmClient(String baseUrl, int maxRetries) {
        this(baseUrl, maxRetries, System.getenv("ANTHROPIC_API_KEY"));
    }

    AnthropicHttpLlmClient(String baseUrl, int maxRetries, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxRetries = maxRetries;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        String payload = translator.buildCompletionPayload(request);
        String body = post(baseUrl + translator.completionPath(), payload);
        return translator.parseCompletionResponse(body);
    }

    @Override
    public EmbedResponse embed(EmbedRequest request) {
        throw new UnsupportedOperationException("Anthropic does not provide an embeddings endpoint.");
    }

    private String post(String url, String body) {
        int attempt = 0;
        long backoffMs = 500;
        while (true) {
            attempt++;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("x-api-key", apiKey);
                }

                HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();

                if (status == 200 || status == 201) return resp.body();

                // 529 = Anthropic overload, retryable; 400 = bad request, not retryable
                boolean retryable = (status == 529 || status == 429 || (status >= 500 && status != 529));
                if (!retryable || attempt > maxRetries) {
                    throw new LlmClientException("Anthropic HTTP " + status + ": " + resp.body());
                }

                long waitMs = resp.headers().firstValue("Retry-After")
                        .map(v -> Long.parseLong(v) * 1000L).orElse(backoffMs);
                Thread.sleep(waitMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);

            } catch (LlmClientException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmClientException("Interrupted during Anthropic retry");
            } catch (IOException e) {
                if (attempt > maxRetries) throw new LlmClientException("IO error: " + e.getMessage());
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
    }
}
