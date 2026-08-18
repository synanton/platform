package org.synanton.synapt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synapt")
public record SynaptProperties(
        Gateway gateway,
        Tenant tenant,
        UiSecurity uiSecurity,
        Validation validation
) {
    public record Gateway(String baseUrl, long timeoutMs) {}
    public record Tenant(String defaultTenant) {}
    public record UiSecurity(String cspMode) {}
    public record Validation(boolean strict) {}

    public SynaptProperties {
        if (gateway == null) gateway = new Gateway("http://gateway:8086", 10000);
        if (tenant == null) tenant = new Tenant("demo");
        if (uiSecurity == null) uiSecurity = new UiSecurity("enforce");
        if (validation == null) validation = new Validation(false);
    }
}
