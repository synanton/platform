package org.synanton.synvault.adapter;

import org.junit.jupiter.api.Test;
import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.synanton.synvault.port.ObjectStorePort;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class PushedContentAdapterTest {

    @Test
    void listsCommaSeparatedRefs() {
        ObjectStorePort unused = unusedStore();
        var props = new SynvaultObjectStoreProperties(
                "http://localhost:9000", "us-east-1", true, "a", "b", "synanton-hot");
        var adapter = new PushedContentAdapter(unused, props);
        var refs = adapter.list("synvault://demo/11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222")
                .toList();
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).scheme()).isEqualTo("synvault");
        assertThat(refs.get(0).uri()).contains("demo/");
    }

    @Test
    void opensStoredObject() throws Exception {
        Map<String, byte[]> store = new ConcurrentHashMap<>();
        store.put("synanton-hot/demo/abc", "payload".getBytes());
        ObjectStorePort objectStore = new ObjectStorePort() {
            @Override
            public void putObject(String bucket, String key, InputStream data, long size, String contentType) {
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
                return "";
            }
        };
        var props = new SynvaultObjectStoreProperties(
                "http://localhost:9000", "us-east-1", true, "a", "b", "synanton-hot");
        var adapter = new PushedContentAdapter(objectStore, props);
        var ref = adapter.list("synvault://demo/abc").toList().get(0);
        try (InputStream in = adapter.open(ref)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("payload");
        }
    }

    private static ObjectStorePort unusedStore() {
        return new ObjectStorePort() {
            @Override
            public void putObject(String bucket, String key, InputStream data, long size, String contentType) {
            }

            @Override
            public InputStream getObject(String bucket, String key) {
                return InputStream.nullInputStream();
            }

            @Override
            public boolean headObject(String bucket, String key) {
                return false;
            }

            @Override
            public String presignedUrl(String bucket, String key, int expiryMinutes) {
                return "";
            }
        };
    }
}
