package org.synanton.gateway.synthesis;

public sealed interface SynthesisResult {
    record Ok(String answer, int promptTokens, int completionTokens, long latencyMs) implements SynthesisResult {}
    record Timeout(long latencyMs) implements SynthesisResult {}
    record Error(String message, long latencyMs) implements SynthesisResult {}
    record Disabled() implements SynthesisResult {}
    record SkippedEmpty() implements SynthesisResult {}
}
