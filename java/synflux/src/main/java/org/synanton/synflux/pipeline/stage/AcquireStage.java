package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ContentPullPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public class AcquireStage implements PipelineStage<ContentRef, AcquiredDocument> {

    private static final Logger log = LoggerFactory.getLogger(AcquireStage.class);
    private static final UUID NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private final ContentPullPort pullPort;

    public AcquireStage(ContentPullPort pullPort) { this.pullPort = pullPort; }

    @Override
    public String name() { return "acquire"; }

    @Override
    public AcquiredDocument apply(ContentRef ref, StageContext ctx) {
        byte[] bytes;
        try (InputStream is = pullPort.open(ctx.tenant(), ref)) {
            bytes = is.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read content: " + ref.uri(), e);
        }
        String sha256 = sha256Hex(bytes);
        UUID contentRefId = uuidv5(NAMESPACE, ctx.tenant() + ":" + sha256);
        return new AcquiredDocument(ref, bytes, sha256, ref.mimeType(), ref.uri(), contentRefId);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // UUIDv5 implementation
    private static UUID uuidv5(UUID namespace, String name) {
        try {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] nsBytes = new byte[16];
            long msb = namespace.getMostSignificantBits();
            long lsb = namespace.getLeastSignificantBits();
            for (int i = 7; i >= 0; i--) { nsBytes[i] = (byte)(msb & 0xff); msb >>= 8; }
            for (int i = 15; i >= 8; i--) { nsBytes[i] = (byte)(lsb & 0xff); lsb >>= 8; }
            byte[] combined = new byte[nsBytes.length + nameBytes.length];
            System.arraycopy(nsBytes, 0, combined, 0, nsBytes.length);
            System.arraycopy(nameBytes, 0, combined, nsBytes.length, nameBytes.length);
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(combined);
            hash[6] = (byte)((hash[6] & 0x0f) | 0x50); // version 5
            hash[8] = (byte)((hash[8] & 0x3f) | 0x80); // variant
            long msbOut = 0, lsbOut = 0;
            for (int i = 0; i < 8; i++) msbOut = (msbOut << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsbOut = (lsbOut << 8) | (hash[i] & 0xff);
            return new UUID(msbOut, lsbOut);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
