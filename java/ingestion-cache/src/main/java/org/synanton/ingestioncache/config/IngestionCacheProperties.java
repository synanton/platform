package org.synanton.ingestioncache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ingestion-cache")
public record IngestionCacheProperties(
    List<String> contactPoints,
    int port,
    String keyspace,
    String localDc
) {
    public IngestionCacheProperties {
        if (contactPoints == null || contactPoints.isEmpty()) contactPoints = List.of("localhost");
        if (port == 0) port = 9042;
        if (keyspace == null) keyspace = "ingestion_cache";
        if (localDc == null) localDc = "datacenter1";
    }
}
