package org.synanton.synvault.port;

import org.junit.jupiter.api.Test;
import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.synanton.synvault.domain.ContentPushResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPushPortTest {

    @Test
    void writesBytesAndReturnsStableContentRefId() {
        Map<String, byte[]> store = new ConcurrentHashMap<>();
        ObjectStorePort objectStore = new ObjectStorePort() {
            @Override
            public void putObject(String bucket, String key, InputStream data, long size, String contentType) {
                try {
                    store.put(bucket + "/" + key, data.readAllBytes());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public InputStream getObject(String bucket, String key) {
                return new ByteArrayInputStream(store.get(bucket + "/" + key));
            }

            @Override
            public boolean headObject(String bucket, String key) {
                return store.containsKey(bucket + "/" + key);
            }

            @Override
            public String presignedUrl(String bucket, String key, int expiryMinutes) {
                return "http://example/" + key;
            }
        };
        var props = new SynvaultObjectStoreProperties(
                "http://localhost:9000", "us-east-1", true, "minioadmin", "minioadmin", "synanton-hot");
        ContentPushPort port = new ContentPushPort(objectStore, props);

        byte[] payload = "hello synanton".getBytes();
        ContentPushResult first = port.write("demo", payload, "lucentrix://1", "text/plain");
        ContentPushResult second = port.write("demo", payload, "lucentrix://1", "text/plain");

        assertThat(first.contentRefId()).isEqualTo(second.contentRefId());
        assertThat(first.sha256()).hasSize(64);
        assertThat(store).containsKey("synanton-hot/demo/" + first.contentRefId());
    }
}
