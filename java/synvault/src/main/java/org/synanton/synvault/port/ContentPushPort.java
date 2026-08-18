package org.synanton.synvault.port;

import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.synanton.synvault.domain.ContentPushResult;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ContentPushPort {

    private static final UUID NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private final ObjectStorePort objectStore;
    private final SynvaultObjectStoreProperties storeProps;

    public ContentPushPort(ObjectStorePort objectStore, SynvaultObjectStoreProperties storeProps) {
        this.objectStore = objectStore;
        this.storeProps = storeProps;
    }

    public ContentPushResult write(String tenant, byte[] bytes, String sourceUri, String mimeType) {
        if (bytes == null) {
            bytes = new byte[0];
        }
        String sha256 = sha256Hex(bytes);
        UUID contentRefId = uuidv5(NAMESPACE, tenant + ":" + sha256);
        String key = tenant + "/" + contentRefId;
        String mime = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
        objectStore.putObject(
                storeProps.hotBucket(),
                key,
                new ByteArrayInputStream(bytes),
                bytes.length,
                mime
        );
        return new ContentPushResult(contentRefId, sha256, bytes.length);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static UUID uuidv5(UUID namespace, String name) {
        try {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] nsBytes = new byte[16];
            long msb = namespace.getMostSignificantBits();
            long lsb = namespace.getLeastSignificantBits();
            for (int i = 7; i >= 0; i--) {
                nsBytes[i] = (byte) (msb & 0xff);
                msb >>= 8;
            }
            for (int i = 15; i >= 8; i--) {
                nsBytes[i] = (byte) (lsb & 0xff);
                lsb >>= 8;
            }
            byte[] combined = new byte[nsBytes.length + nameBytes.length];
            System.arraycopy(nsBytes, 0, combined, 0, nsBytes.length);
            System.arraycopy(nameBytes, 0, combined, nsBytes.length, nameBytes.length);
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(combined);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long msbOut = 0, lsbOut = 0;
            for (int i = 0; i < 8; i++) msbOut = (msbOut << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsbOut = (lsbOut << 8) | (hash[i] & 0xff);
            return new UUID(msbOut, lsbOut);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
