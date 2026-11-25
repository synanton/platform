package org.synanton.synflux.document;

import java.util.List;

/**
 * Resolves an evidence span to the canonical structural artifact. This is the P3a hand-off:
 * callers supply document offsets (or chunk-relative offsets) and receive the exact source text,
 * element identity and geometry without re-parsing or re-chunking.
 */
public final class StructuralEvidenceResolver {

    public record Evidence(
        String sourceVersionId,
        String documentId,
        String chunkId,
        String chunkingConfig,
        int documentStartOffset,
        int documentEndOffset,
        String text,
        List<DocumentStructure.Element> elements,
        String parserName,
        String parserVersion,
        String normalizationVersion
    ) {}

    public Evidence resolveDocumentSpan(DocumentStructure structure, int documentStartOffset,
                                        int documentEndOffset) {
        validateSpan(structure, documentStartOffset, documentEndOffset);
        DocumentStructure.Chunk chunk = structure.chunks().stream()
            .filter(c -> c.documentStartOffset() <= documentStartOffset
                && c.documentEndOffset() >= documentEndOffset)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "evidence span is not contained in a structural chunk: "
                    + documentStartOffset + ".." + documentEndOffset));
        return evidence(structure, chunk, documentStartOffset - chunk.documentStartOffset(),
            documentEndOffset - chunk.documentStartOffset());
    }

    public Evidence resolveChunkSpan(DocumentStructure structure, String chunkId,
                                     int chunkStartOffset, int chunkEndOffset) {
        DocumentStructure.Chunk chunk = structure.chunks().stream()
            .filter(c -> c.chunkId().equals(chunkId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown chunkId: " + chunkId));
        if (chunkStartOffset < 0 || chunkEndOffset < chunkStartOffset
            || chunkEndOffset > chunk.text().length()) {
            throw new IllegalArgumentException("chunk span outside chunk: "
                + chunkStartOffset + ".." + chunkEndOffset);
        }
        return evidence(structure, chunk, chunkStartOffset, chunkEndOffset);
    }

    private static Evidence evidence(DocumentStructure structure, DocumentStructure.Chunk chunk,
                                     int chunkStartOffset, int chunkEndOffset) {
        int documentStart = chunk.documentStartOffset() + chunkStartOffset;
        int documentEnd = chunk.documentStartOffset() + chunkEndOffset;
        validateSpan(structure, documentStart, documentEnd);
        List<DocumentStructure.Element> elements = structure.elements().stream()
            .filter(e -> e.chunkId().equals(chunk.chunkId()))
            .filter(e -> e.documentEndOffset() > documentStart
                && e.documentStartOffset() < documentEnd)
            .toList();
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("evidence span has no structural element: "
                + documentStart + ".." + documentEnd);
        }
        return new Evidence(
            structure.sourceVersionId(), structure.documentId(), chunk.chunkId(),
            structure.chunkingConfig(), documentStart, documentEnd,
            structure.text().substring(documentStart, documentEnd), elements,
            structure.parserName(), structure.parserVersion(), structure.normalizationVersion());
    }

    private static void validateSpan(DocumentStructure structure, int start, int end) {
        if (start < 0 || end < start || end > structure.text().length()) {
            throw new IllegalArgumentException("document span outside canonical text: " + start + ".." + end);
        }
    }
}
