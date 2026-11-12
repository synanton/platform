package org.synanton.synflux.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.RelationValidationOutcome;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.synflux.extraction.graph.RelationValidation;
import org.synanton.synflux.extraction.graph.RelationValidator;

class DefaultCanonicalRelationValidatorTest {

    private static final String TENANT = "tenant-relation";
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID REF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String RELATION_ID = "relation-1";

    private final ResolutionGraphLookup graph = mock(ResolutionGraphLookup.class);
    private final ExtractedMentionGraphReadPort extractedReadPort =
            mock(ExtractedMentionGraphReadPort.class);
    private final RelationValidator syntology = mock(RelationValidator.class);
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final DefaultCanonicalRelationValidator validator = new DefaultCanonicalRelationValidator(
            graph, extractedReadPort, syntology, cache, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void seed() throws Exception {
        when(extractedReadPort.getExtractedRelation(TENANT, RELATION_ID)).thenReturn(Optional.of(
                new ExtractedRelationView(RELATION_ID, TENANT, VERSION.toString(),
                        "m-subject", "m-object", "requires", REF + ":0", 0, 20)));
        when(graph.getActiveResolutions(TENANT, List.of("m-subject", "m-object"))).thenReturn(List.of(
                new ActiveResolution("m-subject", "e-subject", "MaintenanceSystem"),
                new ActiveResolution("m-object", "e-object", "Protocol")));
        ObjectMapper mapper = new ObjectMapper();
        when(cache.listExtractionResults(TENANT, REF)).thenReturn(List.of(new ExtractionResultRow(
                TENANT, REF, 0, VERSION, "[]",
                mapper.writeValueAsString(List.of(new RelationCandidate(
                        "m-subject", "requires", "m-object", "ref:0", 0, 20))), NOW)));
    }

    @Test
    void validTripleIsPersistedWithoutGraphMutation() {
        when(syntology.validate(TENANT, "MaintenanceSystem", "requires", "Protocol", "1.0.0"))
                .thenReturn(new RelationValidation(true, List.of()));

        var result = validator.validate(TENANT, "1.0.0", RELATION_ID);

        assertThat(result.outcome()).isEqualTo(RelationValidationOutcome.VALID);
        assertThat(result.subjectEntityId()).isEqualTo("e-subject");
        assertThat(result.objectEntityId()).isEqualTo("e-object");
        ArgumentCaptor<RelationPromotionResultRow> row = ArgumentCaptor.forClass(RelationPromotionResultRow.class);
        verify(cache).upsertRelationPromotionResult(row.capture());
        assertThat(row.getValue().promotionStatus()).isEqualTo("VALID");
        assertThat(row.getValue().canonicalSubjectId()).isEqualTo("e-subject");
        assertThat(row.getValue().ontologyVersion()).isEqualTo("1.0.0");
        assertThat(row.getValue().strategyVersion()).isEqualTo(DefaultCanonicalRelationValidator.STRATEGY_VERSION);
    }

    @Test
    void invalidDomainOrPredicateIsInvalid() {
        when(syntology.validate(any(), any(), any(), any(), any())).thenReturn(
                new RelationValidation(false, List.of(new RelationValidation.Violation(
                        "domain_mismatch", "bad domain", "requires"))));

        var result = validator.validate(TENANT, "1.0.0", RELATION_ID);

        assertThat(result.outcome()).isEqualTo(RelationValidationOutcome.INVALID);
        assertThat(result.violations()).anySatisfy(violation ->
                assertThat(violation.code()).isEqualTo("domain_mismatch"));
        verify(cache).upsertRelationPromotionResult(any());
    }

    @Test
    void missingOrMultipleActiveResolutionIsInvalidWithoutSyntology() {
        when(graph.getActiveResolutions(TENANT, List.of("m-subject", "m-object"))).thenReturn(List.of(
                new ActiveResolution("m-subject", "e-subject", "MaintenanceSystem")));

        var result = validator.validate(TENANT, "1.0.0", RELATION_ID);

        assertThat(result.outcome()).isEqualTo(RelationValidationOutcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("MISSING_ACTIVE_RESOLUTION");
        verify(syntology, never()).validate(any(), any(), any(), any(), any());
    }

    @Test
    void repeatedActiveResolutionIsInvalid() {
        when(graph.getActiveResolutions(TENANT, List.of("m-subject", "m-object"))).thenReturn(List.of(
                new ActiveResolution("m-subject", "e-subject", "MaintenanceSystem"),
                new ActiveResolution("m-subject", "e-subject-2", "MaintenanceSystem"),
                new ActiveResolution("m-object", "e-object", "Protocol")));

        var result = validator.validate(TENANT, "1.0.0", RELATION_ID);

        assertThat(result.outcome()).isEqualTo(RelationValidationOutcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("MULTIPLE_ACTIVE_RESOLUTIONS");
    }

    @Test
    void missingExtractedRelationIsInvalid() {
        when(extractedReadPort.getExtractedRelation(TENANT, RELATION_ID)).thenReturn(Optional.empty());

        var result = validator.validate(TENANT, "1.0.0", RELATION_ID);

        assertThat(result.outcome()).isEqualTo(RelationValidationOutcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("EXTRACTED_RELATION_NOT_FOUND");
        verify(syntology, never()).validate(any(), any(), any(), any(), any());
    }

    @Test
    void missingCassandraEvidenceIsInvalid() {
        when(cache.listExtractionResults(TENANT, REF)).thenReturn(List.of());

        var result = validator.validate(TENANT, "1.0.0", RELATION_ID);

        assertThat(result.outcome()).isEqualTo(RelationValidationOutcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("EXTRACTED_EVIDENCE_NOT_FOUND");
    }

    @Test
    void syntologyFailureIsRetryableAndNotPersistedAsInvalid() {
        when(syntology.validate(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("syntology down"));

        assertThatThrownBy(() -> validator.validate(TENANT, "1.0.0", RELATION_ID))
                .isInstanceOf(RetryableProcessingException.class);
        verify(cache, never()).upsertRelationPromotionResult(any());
    }
}
