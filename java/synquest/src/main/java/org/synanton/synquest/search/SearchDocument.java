package org.synanton.synquest.search;

import java.util.List;
import java.util.UUID;
import org.synanton.synquest.api.dto.Hit;

/**
 * Engine-neutral representation of one ranked chunk (#550). The Lucene adapter
 * maps stored fields into this record; the API layer maps it to {@link Hit}.
 */
public record SearchDocument(
        UUID contentRefId,
        int chunkOrdinal,
        double score,
        double scoreDense,
        double scoreLexical,
        int rankDense,
        int rankLexical,
        String snippet,
        String sourceUri,
        int pageStart,
        int pageEnd,
        String sectionPath,
        String heading,
        List<String> sourceElements,
        int tokenCount,
        String structuredContent,
        boolean isPartialSection,
        String ingestUsage,
        Double scoreRerank,
        String sectionId,
        String expandedFrom,
        SearchDocumentType documentType,
        String entityId,
        String entityLabel,
        List<String> entityAliases,
        String entityType,
        String entityDescription) {

    public SearchDocument(UUID contentRefId, int chunkOrdinal, double score, double scoreDense,
                          double scoreLexical, int rankDense, int rankLexical, String snippet,
                          String sourceUri, int pageStart, int pageEnd, String sectionPath,
                          String heading, List<String> sourceElements, int tokenCount,
                          String structuredContent, boolean isPartialSection, String ingestUsage,
                          Double scoreRerank, String sectionId, String expandedFrom) {
        this(contentRefId, chunkOrdinal, score, scoreDense, scoreLexical, rankDense, rankLexical,
                snippet, sourceUri, pageStart, pageEnd, sectionPath, heading, sourceElements,
                tokenCount, structuredContent, isPartialSection, ingestUsage, scoreRerank,
                sectionId, expandedFrom, SearchDocumentType.CHUNK, null, null, List.of(), null, null);
    }

    public static SearchDocument from(Hit hit) {
        return new SearchDocument(
                hit.contentRefId(), hit.chunkOrdinal(), hit.score(), hit.scoreDense(), hit.scoreLexical(),
                hit.rankDense(), hit.rankLexical(), hit.snippet(), hit.sourceUri(), hit.pageStart(),
                hit.pageEnd(), hit.sectionPath(), hit.heading(), hit.sourceElements(), hit.tokenCount(),
                hit.structuredContent(), hit.isPartialSection(), hit.ingestUsage(), hit.scoreRerank(),
                hit.sectionId(), hit.expandedFrom());
    }

    public Hit toHit() {
        if (documentType == SearchDocumentType.ENTITY) {
            throw new IllegalStateException("entity documents cannot be mapped to a chunk Hit");
        }
        return new Hit(contentRefId, chunkOrdinal, score, scoreDense, scoreLexical, rankDense, rankLexical,
                snippet, sourceUri, pageStart, pageEnd, sectionPath, heading, sourceElements, tokenCount,
                structuredContent, isPartialSection, ingestUsage, scoreRerank, sectionId, expandedFrom);
    }

    public String chunkId() {
        return contentRefId + ":" + chunkOrdinal;
    }
}
