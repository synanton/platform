package org.synanton.synvault.port;

import java.io.InputStream;

public interface ObjectStorePort {
    void putObject(String bucket, String key, InputStream data, long size, String contentType);
    InputStream getObject(String bucket, String key);
    boolean headObject(String bucket, String key);
    String presignedUrl(String bucket, String key, int expiryMinutes);
}
