package org.synanton.gpu.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gpu-gateway")
public class GpuGatewayProperties {

    private int grpcPort = 9090;
    private int maxInboundMessageSizeBytes = 4 * 1024 * 1024 + 65_536;
    private Dispatch dispatch = new Dispatch();
    private Idempotency idempotency = new Idempotency();

    public static class Dispatch {
        private String strategy = "direct";
        private String vllmEndpoint = "http://vllm-service:8000";
        private int timeoutMs = 120_000;

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getVllmEndpoint() { return vllmEndpoint; }
        public void setVllmEndpoint(String vllmEndpoint) { this.vllmEndpoint = vllmEndpoint; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Idempotency {
        private int retentionHours = 24;

        public int getRetentionHours() { return retentionHours; }
        public void setRetentionHours(int retentionHours) { this.retentionHours = retentionHours; }
    }

    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }
    public int getMaxInboundMessageSizeBytes() { return maxInboundMessageSizeBytes; }
    public void setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) { this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes; }
    public Dispatch getDispatch() { return dispatch; }
    public void setDispatch(Dispatch dispatch) { this.dispatch = dispatch; }
    public Idempotency getIdempotency() { return idempotency; }
    public void setIdempotency(Idempotency idempotency) { this.idempotency = idempotency; }
}
