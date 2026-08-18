package org.synanton.ingestioncache.codec;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class LZ4EmbeddingCodec {

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();
    private static final LZ4Compressor COMPRESSOR = FACTORY.fastCompressor();
    private static final LZ4FastDecompressor DECOMPRESSOR = FACTORY.fastDecompressor();

    private LZ4EmbeddingCodec() {}

    public static byte[] compress(float[] embedding) {
        byte[] raw = toFp16Bytes(embedding);
        byte[] compressed = COMPRESSOR.compress(raw);
        // Prefix with original length (4 bytes) for decompression
        ByteBuffer buf = ByteBuffer.allocate(4 + compressed.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(raw.length);
        buf.put(compressed);
        return buf.array();
    }

    public static float[] decompress(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int originalLen = buf.getInt();
        byte[] compressed = new byte[data.length - 4];
        buf.get(compressed);
        byte[] raw = new byte[originalLen];
        DECOMPRESSOR.decompress(compressed, 0, raw, 0, originalLen);
        return fromFp16Bytes(raw);
    }

    private static byte[] toFp16Bytes(float[] embedding) {
        // Store as fp32 bytes (simple, compatible)
        ByteBuffer buf = ByteBuffer.allocate(embedding.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : embedding) buf.putFloat(f);
        return buf.array();
    }

    private static float[] fromFp16Bytes(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = buf.getFloat();
        return result;
    }
}
