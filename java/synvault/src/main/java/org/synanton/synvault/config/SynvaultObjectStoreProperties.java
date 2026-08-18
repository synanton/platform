package org.synanton.synvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synvault.object-store")
public record SynvaultObjectStoreProperties(
    String endpoint,
    String region,
    boolean pathStyleAccess,
    String accessKey,
    String secretKey,
    String hotBucket
) {
    public SynvaultObjectStoreProperties {
        if (endpoint == null) endpoint = "http://localhost:9000";
        if (region == null) region = "us-east-1";
        if (accessKey == null) accessKey = "minioadmin";
        if (secretKey == null) secretKey = "minioadmin";
        if (hotBucket == null) hotBucket = "synanton-hot";
    }
}
