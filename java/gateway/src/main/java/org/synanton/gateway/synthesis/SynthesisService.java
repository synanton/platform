package org.synanton.gateway.synthesis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;
import org.synanton.gateway.gpu.GpuSynthesisAdapter;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.LlmClient;
import org.synanton.llm.LlmClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SynthesisService {

    private static final Logger log = LoggerFactory.getLogger(SynthesisService.class);

    private final GatewayProperties props;
    private final LlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final Optional<GpuSynthesisAdapter> gpuAdapter;

    public SynthesisService(
            GatewayProperties props,
            LlmClient llmClient,
            PromptBuilder promptBuilder,
            Optional<GpuSynthesisAdapter> gpuAdapter) {
        this.props = props;
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.gpuAdapter = gpuAdapter;
    }

    public SynthesisResult synthesise(String query, List<Hit> hits, GraphResult graph) {
        return synthesise(query, hits, graph, "default", Map.of());
    }

    public SynthesisResult synthesise(
            String query,
            List<Hit> hits,
            GraphResult graph,
            String tenantId,
            Map<String, String> traceContext) {

        if (!props.synthesis().enabled()) {
            return new SynthesisResult.Disabled();
        }

        boolean hitsEmpty = hits == null || hits.isEmpty();
        boolean graphEmpty = graph == null
                || (graph.entities().isEmpty() && graph.edges().isEmpty());

        if (hitsEmpty && graphEmpty) {
            return new SynthesisResult.SkippedEmpty();
        }

        PromptBuilder.PromptInput input = promptBuilder.build(
                query,
                hits != null ? hits : List.of(),
                graph
        );

        if (gpuAdapter.isPresent()) {
            Optional<SynthesisResult> gpuResult = gpuAdapter.get().synthesise(input, tenantId, traceContext);
            if (gpuResult.isPresent()) {
                log.debug("Synthesis served by GPU plane");
                return gpuResult.get();
            }
            log.debug("GPU plane degraded, falling back to CPU LlmClient");
        }

        return synthesisViaCpu(input);
    }

    private SynthesisResult synthesisViaCpu(PromptBuilder.PromptInput input) {
        String userMessage = "Context:\n" + input.context() + "\n\nQuestion: " + input.query();
        CompletionRequest request = new CompletionRequest(
                props.synthesis().model(),
                input.systemPrompt(),
                userMessage,
                props.synthesis().temperature(),
                props.synthesis().maxTokens()
        );

        long start = System.currentTimeMillis();
        try {
            CompletionResponse response = llmClient.complete(request);
            long latency = System.currentTimeMillis() - start;
            log.debug("Synthesis OK (CPU): {} tokens in {}ms", response.completionTokens(), latency);
            return new SynthesisResult.Ok(response.text(), response.promptTokens(), response.completionTokens(), latency);
        } catch (LlmClientException e) {
            long latency = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("timeout") || msg.contains("Timeout") || latency >= props.synthesis().timeoutMs()) {
                log.warn("Synthesis timed out after {}ms", latency);
                return new SynthesisResult.Timeout(latency);
            }
            log.warn("Synthesis error after {}ms: {}", latency, msg);
            return new SynthesisResult.Error(msg, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Synthesis unexpected error: {}", e.getMessage());
            return new SynthesisResult.Error(e.getMessage(), latency);
        }
    }
}
