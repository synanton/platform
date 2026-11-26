package org.synanton.synflux.extraction.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.synflux.config.ExtractionGraphProperties;

/** A4 isolated persistence proof; no cluster graph or production write is involved. */
class A4IsolatedGraphPersistenceTest {

    private static final String TENANT = "it-books-a4";
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID REF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Map<String, RelationSpec> A5 = a5Matrix();

    @Test
    void validCandidateIsPersistedAndInvalidCandidateIsExcluded() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        GraphWriteClient graph = mock(GraphWriteClient.class);
        RelationValidator validator = (tenant, subjectType, predicate, objectType, version) -> {
            RelationSpec spec = A5.get(predicate);
            return new RelationValidation(spec != null
                && spec.subjects.contains(subjectType) && spec.objects.contains(objectType), List.of());
        };
        when(cache.claimGraphCommit(any())).thenReturn(true);
        when(graph.upsertMentions(any())).thenReturn(3);
        when(graph.upsertRelations(any())).thenReturn(1);

        GraphWriter writer = new GraphWriter(cache, validator, graph,
            new ExtractionGraphProperties(true, "http://unused", "http://unused", "it-books-v1"), CLOCK);

        ExtractionResult result = new ExtractionResult(
            List.of(
                new PreEntity("b", "Book", "Book", 0, 4, "ctx"),
                new PreEntity("c", "Chapter", "Chapter", 5, 12, "ctx"),
                new PreEntity("a", "Author", "Author", 13, 19, "ctx")),
            List.of(
                new RelationCandidate("b", "HAS_CHAPTER", "c", REF + ":0", 0, 19),
                new RelationCandidate("c", "AUTHORED_BY", "a", REF + ":0", 0, 19)));

        GraphCommitResult outcome = writer.commit(request(), List.of(chunk()), Map.of(0, result));

        assertThat(outcome.accepted()).isEqualTo(1);
        assertThat(outcome.rejected()).isEqualTo(1);
        assertThat(outcome.rejectionCounts()).containsEntry("ONTOLOGY_INVALID", 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRelation>> relations = ArgumentCaptor.forClass(List.class);
        verify(graph).upsertRelations(relations.capture());
        assertThat(relations.getValue()).hasSize(1);
        GraphRelation accepted = relations.getValue().getFirst();
        assertThat(accepted.predicate()).isEqualTo("HAS_CHAPTER");
        assertThat(accepted.subjectMentionId()).isEqualTo(StableIds.mentionId(
            TENANT, VERSION.toString(), REF + ":0", 0, 4, "Book"));
        assertThat(accepted.objectMentionId()).isEqualTo(StableIds.mentionId(
            TENANT, VERSION.toString(), REF + ":0", 5, 12, "Chapter"));
        assertThat(accepted.evidenceDocumentOffsetStart()).isEqualTo(100);
        assertThat(accepted.sourceId()).isEqualTo("source");
        assertThat(accepted.revisionId()).isEqualTo(VERSION.toString());
        assertThat(relations.getValue()).noneMatch(r -> r.predicate().equals("AUTHORED_BY"));
    }

    @Test
    void rejectedOnlyResultWritesNoCoveredSteps() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        GraphWriteClient graph = mock(GraphWriteClient.class);
        RelationValidator invalid = (tenant, subjectType, predicate, objectType, version) ->
            new RelationValidation(false, List.of(
                new RelationValidation.Violation("domain_mismatch", "invalid endpoint", "domain")));
        when(cache.claimGraphCommit(any())).thenReturn(true);
        when(graph.upsertMentions(any())).thenReturn(3);

        GraphWriter writer = new GraphWriter(cache, invalid, graph,
            new ExtractionGraphProperties(true, "http://unused", "http://unused", "it-books-v1"), CLOCK);
        ExtractionResult result = new ExtractionResult(
            List.of(new PreEntity("b", "Book", "Book", 0, 4, "ctx"),
                new PreEntity("c", "Chapter", "Chapter", 5, 12, "ctx")),
            List.of(new RelationCandidate("b", "AUTHORED_BY", "c", REF + ":0", 0, 12)));

        GraphCommitResult outcome = writer.commit(request(), List.of(chunk()), Map.of(0, result));

        assertThat(outcome.accepted()).isZero();
        assertThat(outcome.rejected()).isEqualTo(1);
        verify(graph, never()).upsertRelations(any());
    }

    private static ExtractionRequestRow request() {
        return new ExtractionRequestRow(TENANT, "upload", "source", "resource", UUID.randomUUID(),
            VERSION, REF, "{}", NOW, NOW, "KNOWLEDGE_READY", 0, null, null, false, 1);
    }

    private static ChunkRow chunk() {
        return new ChunkRow(TENANT, REF, 0, "Book Chapter Author", "sha", -1, -1, "", "", "", "[]",
            0, "", false, ChunkRow.PUBLIC_ONLY, "", "", 0, null, null, 100, 119);
    }

    private static Map<String, RelationSpec> a5Matrix() {
        Map<String, RelationSpec> m = new LinkedHashMap<>();
        m.put("AUTHORED_BY", new RelationSpec(Set.of("Book"), Set.of("Author")));
        m.put("HAS_CHAPTER", new RelationSpec(Set.of("Book"), Set.of("Chapter")));
        m.put("HAS_SECTION", new RelationSpec(Set.of("Chapter"), Set.of("Section")));
        m.put("COVERS_TOPIC", new RelationSpec(Set.of("Book", "Chapter", "Section"), Set.of("Topic")));
        m.put("DEFINES", new RelationSpec(Set.of("Chapter", "Section"), Set.of("Concept", "Definition")));
        m.put("USES", new RelationSpec(
            Set.of("Chapter", "Section", "Algorithm", "DataStructure", "Database", "DistributedSystem"),
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem")));
        m.put("DEPENDS_ON", new RelationSpec(
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem"),
            Set.of("Algorithm", "Concept", "DataStructure", "Database", "DistributedSystem")));
        m.put("PART_OF", new RelationSpec(
            Set.of("Chapter", "Section", "Topic"), Set.of("Book", "Chapter", "Section")));
        return Map.copyOf(m);
    }

    private record RelationSpec(Set<String> subjects, Set<String> objects) {}
}
