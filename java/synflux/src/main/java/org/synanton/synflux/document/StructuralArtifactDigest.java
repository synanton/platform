package org.synanton.synflux.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Canonical digest of a structural artifact. The record binds source identity, parser/normalization
 * provenance, pages, chunks, elements, offsets, geometry and text.
 */
public final class StructuralArtifactDigest {
    private StructuralArtifactDigest() {
    }

    public static String sha256(DocumentStructure structure) {
        StringBuilder canonical = new StringBuilder("structural-artifact-v1\n");
        field(canonical, "tenantId", structure.tenantId());
        field(canonical, "sourceId", structure.sourceId());
        field(canonical, "sourceVersionId", structure.sourceVersionId());
        field(canonical, "documentId", structure.documentId());
        field(canonical, "mediaType", structure.mediaType());
        field(canonical, "normalizationVersion", structure.normalizationVersion());
        field(canonical, "chunkingConfig", structure.chunkingConfig());
        field(canonical, "parserName", structure.parserName());
        field(canonical, "parserVersion", structure.parserVersion());
        field(canonical, "text", structure.text());
        for (DocumentStructure.Page page : structure.pages()) {
            canonical.append("page=").append(page.pageNumber()).append('|')
                .append(Float.toString(page.width())).append('|')
                .append(Float.toString(page.height())).append('|')
                .append(page.coordinateSystem()).append('|').append(page.unit()).append('\n');
        }
        for (DocumentStructure.Chunk chunk : structure.chunks()) {
            canonical.append("chunk=").append(chunk.chunkId()).append('|')
                .append(chunk.readingOrder()).append('|').append(chunk.sectionPath()).append('|')
                .append(chunk.documentStartOffset()).append('|').append(chunk.documentEndOffset())
                .append('|').append(chunk.text()).append('|')
                .append(String.join(",", chunk.elementIds())).append('\n');
        }
        for (DocumentStructure.Element element : structure.elements()) {
            canonical.append("element=").append(element.elementId()).append('|')
                .append(element.pageNumber()).append('|').append(element.elementType()).append('|')
                .append(element.readingOrder()).append('|').append(element.chunkId()).append('|')
                .append(element.chunkStartOffset()).append('|').append(element.chunkEndOffset()).append('|')
                .append(element.documentStartOffset()).append('|').append(element.documentEndOffset())
                .append('|').append(element.geometryStatus()).append('|')
                .append(element.boundingBox() == null ? "-" : bbox(element.boundingBox())).append('|')
                .append(element.parserName()).append('|').append(element.parserVersion()).append('|')
                .append(element.text()).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String bbox(DocumentStructure.BoundingBox box) {
        return Float.toString(box.x0()) + "," + Float.toString(box.y0()) + ","
            + Float.toString(box.x1()) + "," + Float.toString(box.y1());
    }

    private static void field(StringBuilder target, String name, String value) {
        String safe = value == null ? "" : value;
        target.append(name).append('=').append(safe.length()).append(':').append(safe).append('\n');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
