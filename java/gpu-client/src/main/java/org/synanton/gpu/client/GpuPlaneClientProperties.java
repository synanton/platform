package org.synanton.gpu.client;

/**
 * Connection settings for the GPU plane (gRPC {@code synanton.gpu.v1}). This is a plain
 * JavaBean, so each service binds it under its own prefix, e.g.
 * {@code @Bean @ConfigurationProperties("gpu-plane")}.
 */
public class GpuPlaneClientProperties {

    private String endpoint = "localhost:9090";
    private String modelVersion = "latest";
    private int timeoutMs = 120_000;
    /** Tenant used by the tenant-less {@code LlmClient.embed(request)}; null means such calls fail. */
    private String defaultTenant;
    private Tls tls = new Tls();
    private Retry retry = new Retry();

    public static class Tls {
        private boolean enabled = true;
        private String caPath;
        private String certPath;
        private String keyPath;
        /** Optional TLS authority override (a server-certificate SAN, e.g. gpu-gateway). */
        private String authority;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCaPath() { return caPath; }
        public void setCaPath(String caPath) { this.caPath = caPath; }
        public String getCertPath() { return certPath; }
        public void setCertPath(String certPath) { this.certPath = certPath; }
        public String getKeyPath() { return keyPath; }
        public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
        public String getAuthority() { return authority; }
        public void setAuthority(String authority) { this.authority = authority; }
    }

    /** Retries apply only to transient outcomes (see {@link GpuErrorCodes#isTransient}). */
    public static class Retry {
        private int maxAttempts = 3;
        private int backoffBaseMs = 500;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getBackoffBaseMs() { return backoffBaseMs; }
        public void setBackoffBaseMs(int backoffBaseMs) { this.backoffBaseMs = backoffBaseMs; }
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getDefaultTenant() { return defaultTenant; }
    public void setDefaultTenant(String defaultTenant) { this.defaultTenant = defaultTenant; }
    public Tls getTls() { return tls; }
    public void setTls(Tls tls) { this.tls = tls; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
}
