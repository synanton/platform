package org.synanton.gateway.gpu;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.gpu")
public class GpuExecutionClientProperties {

    private boolean enabled = false;
    private String endpoint = "localhost:9090";
    private String modelVersion = "latest";
    private Tls tls = new Tls();
    private int timeoutMs = 120_000;
    private Retry retry = new Retry();

    public static class Tls {
        private boolean enabled = false;
        private String certPath;
        private String keyPath;
        private String caPath;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCertPath() { return certPath; }
        public void setCertPath(String certPath) { this.certPath = certPath; }
        public String getKeyPath() { return keyPath; }
        public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
        public String getCaPath() { return caPath; }
        public void setCaPath(String caPath) { this.caPath = caPath; }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private int backoffBaseMs = 500;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getBackoffBaseMs() { return backoffBaseMs; }
        public void setBackoffBaseMs(int backoffBaseMs) { this.backoffBaseMs = backoffBaseMs; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public Tls getTls() { return tls; }
    public void setTls(Tls tls) { this.tls = tls; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
}
