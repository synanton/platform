package org.synanton.synflux.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RAKG PR #3 graph-write configuration. Disabled by default. */
@ConfigurationProperties(prefix = "synflux.extraction-engine.graph")
public record ExtractionGraphProperties(
        boolean enabled,
        String syntologyUrl,
        String relixUrl,
        String ontologyVersion) {

    public String syntologyUrlOrDefault() {
        return syntologyUrl == null || syntologyUrl.isBlank() ? "http://syntology:8080" : syntologyUrl;
    }

    public String relixUrlOrDefault() {
        return relixUrl == null || relixUrl.isBlank() ? "http://relix:8080" : relixUrl;
    }

    public String ontologyVersionOrDefault() {
        return ontologyVersion == null || ontologyVersion.isBlank() ? "active" : ontologyVersion;
    }
}
