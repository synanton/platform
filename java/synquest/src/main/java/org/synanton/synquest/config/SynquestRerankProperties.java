package org.synanton.synquest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.synanton.gpu.client.RerankPromptFormat;

/**
 * {@code synquest.rerank.*}: optional cross-encoder reranking after RRF fusion (retrieval
 * benchmark B2, T10). The reranker is created only under the gpu-plane profile with
 * {@code enabled=true}. A request with {@code rerank: true} otherwise fails with 503.
 */
@ConfigurationProperties("synquest.rerank")
public class SynquestRerankProperties {
    private boolean enabled = false;
    /** Logical model ID in the GPU Gateway catalog. */
    private String model = "synanton-qwen3-reranker-0.6b";
    /** QWEN3 for Qwen3-Reranker (without it, scores invert); PLAIN for classic cross-encoders. */
    private RerankPromptFormat promptFormat = RerankPromptFormat.QWEN3;
    private String instruction;
    private int defaultCandidates = 50;
    private int maxCandidates = 100;
    /** Passages are cut to this many chars (the reranker's context is 8192 tokens). */
    private int maxPassageChars = 6000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public RerankPromptFormat getPromptFormat() { return promptFormat; }
    public void setPromptFormat(RerankPromptFormat promptFormat) { this.promptFormat = promptFormat; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public int getDefaultCandidates() { return defaultCandidates; }
    public void setDefaultCandidates(int defaultCandidates) { this.defaultCandidates = defaultCandidates; }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
    public int getMaxPassageChars() { return maxPassageChars; }
    public void setMaxPassageChars(int maxPassageChars) { this.maxPassageChars = maxPassageChars; }
}
