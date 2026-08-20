package org.synanton.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.gateway.client.PlannerClient;
import org.synanton.gateway.client.RelixClient;
import org.synanton.gateway.client.SynquestClient;
import org.synanton.gateway.gpu.GpuExecutionClient;
import org.synanton.gateway.gpu.GpuExecutionClientProperties;
import org.synanton.gateway.gpu.GpuSynthesisAdapter;
import org.synanton.gateway.plan.FusionEngine;
import org.synanton.gateway.plan.PlanExecutor;
import org.synanton.gateway.synthesis.PromptBuilder;
import org.synanton.gateway.synthesis.SynthesisService;
import org.synanton.llm.HttpLlmClient;
import org.synanton.llm.LlmClient;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({GatewayProperties.class, GpuExecutionClientProperties.class})
public class GatewayConfig {

    @Bean
    public ExecutorService gatewayExecutor(GatewayProperties props) {
        return Executors.newFixedThreadPool(props.executor().parallelism());
    }

    @Bean
    public PlannerClient plannerClient(GatewayProperties props) {
        WebClient wc = WebClient.builder().baseUrl(props.planner().baseUrl()).build();
        return new PlannerClient(wc, props.planner().timeoutMs(), props.planner().retryOnce5xx());
    }

    @Bean
    public SynquestClient synquestClient(GatewayProperties props) {
        WebClient wc = WebClient.builder().baseUrl(props.synquest().baseUrl()).build();
        return new SynquestClient(wc, props.synquest().timeoutMs());
    }

    @Bean
    public RelixClient relixClient(GatewayProperties props) {
        WebClient wc = WebClient.builder().baseUrl(props.relix().baseUrl()).build();
        return new RelixClient(wc, props.relix().timeoutMs());
    }

    @Bean
    public FusionEngine fusionEngine() {
        return new FusionEngine();
    }

    @Bean
    public PlanExecutor planExecutor(
            SynquestClient synquestClient,
            RelixClient relixClient,
            FusionEngine fusionEngine,
            ExecutorService gatewayExecutor,
            GatewayProperties props
    ) {
        return new PlanExecutor(synquestClient, relixClient, fusionEngine, gatewayExecutor, props);
    }

    @Bean
    public LlmClient synthesisLlmClient(GatewayProperties props) {
        return new HttpLlmClient(props.synthesis().baseUrl(), 1);
    }

    @Bean
    public PromptBuilder promptBuilder(GatewayProperties props) {
        return new PromptBuilder(props.synthesis());
    }

    @Bean
    public GpuExecutionClient gpuExecutionClient(GpuExecutionClientProperties gpuProps) {
        return new GpuExecutionClient(gpuProps);
    }

    @Bean
    public Optional<GpuSynthesisAdapter> gpuSynthesisAdapter(
            GpuExecutionClientProperties gpuProps,
            GpuExecutionClient gpuExecutionClient,
            GatewayProperties gatewayProps,
            ObjectMapper objectMapper
    ) {
        if (!gpuProps.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new GpuSynthesisAdapter(
                gpuExecutionClient, gpuProps, gatewayProps.synthesis(), objectMapper));
    }

    @Bean
    public SynthesisService synthesisService(
            GatewayProperties props,
            LlmClient synthesisLlmClient,
            PromptBuilder promptBuilder,
            Optional<GpuSynthesisAdapter> gpuSynthesisAdapter
    ) {
        return new SynthesisService(props, synthesisLlmClient, promptBuilder, gpuSynthesisAdapter);
    }
}
