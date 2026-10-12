package org.synanton.synflux.pipeline.stage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.synanton.synflux.chunking.DocumentStructureBuilder;
import org.synanton.synflux.chunking.SectionNode;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;

/**
 * RQ1 third arm: one chunk per document section (heading + concatenated
 * element texts), hierarchy attached. Unlike {@link SemanticChunkStage}
 * (token-budgeted batches within sections) and {@link ChunkStage}
 * (fixed word windows), section boundaries are never split — tests whether
 * structural units retrieve better as wholes. Flat-text fallback mirrors
 * SemanticChunkStage for unstructured payloads.
 */
public class HierarchicalChunkStage implements PipelineStage<ParsedDocument, ChunkedDocument> {

    private final DocumentStructureBuilder structureBuilder = new DocumentStructureBuilder();

    @Override
    public String name() { return "hierarchical-chunk"; }

    @Override
    public ChunkedDocument apply(ParsedDocument doc, StageContext ctx) {
        String documentId = doc.acquired().contentRefId().toString();
        List<SemanticChunk> chunks = new ArrayList<>();
        var payload = doc.documentPayload();
        if (payload != null && !payload.getElementsList().isEmpty()) {
            for (SectionNode section : structureBuilder.build(payload.getElementsList())) {
                collectSections(section, "", documentId, chunks);
            }
        }
        if (chunks.isEmpty() && doc.text() != null && !doc.text().isBlank()) {
            chunks.add(sectionChunk(documentId, 0, "", "root", 0, doc.text(), ""));
        }
        return new ChunkedDocument(doc, chunks);
    }

    private void collectSections(SectionNode section, String parentId, String documentId,
                                 List<SemanticChunk> chunks) {
        StringBuilder text = new StringBuilder();
        if (section.heading() != null && !section.heading().isBlank()) {
            text.append(section.heading()).append("\n");
        }
        for (var el : section.elements()) {
            if (el.getText() != null && !el.getText().isBlank()) {
                text.append(el.getText()).append("\n");
            }
        }
        String body = text.toString().strip();
        if (!body.isEmpty()) {
            chunks.add(sectionChunk(documentId, chunks.size(), section.id(),
                    section.heading(), section.headingLevel(), body, parentId));
        }
        for (SectionNode child : section.children()) {
            collectSections(child, section.id(), documentId, chunks);
        }
    }

    private static SemanticChunk sectionChunk(String documentId, int ordinal, String sectionId,
                                              String heading, int level, String text, String parentId) {
        return new SemanticChunk(
                documentId + "-s" + ordinal, documentId, ordinal,
                SemanticChunk.ChunkType.SECTION, text, null,
                List.of(), heading, List.of(), -1, -1,
                Math.max(1, text.length() / 4), false, Map.of(),
                SemanticChunk.PUBLIC_ONLY, sha256(text),
                sectionId, parentId, level);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
