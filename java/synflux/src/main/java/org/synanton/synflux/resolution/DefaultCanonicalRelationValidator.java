package org.synanton.synflux.resolution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.RelationValidationOutcome;
import org.synanton.common.resolution.RelationValidationResult;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.ValidationViolation;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.synanton.synflux.extraction.graph.RelationValidation;
import org.synanton.synflux.extraction.graph.RelationValidator;

/**
 * PR #4c.2: validate a canonical candidate relation from verified active
 * resolutions + Syntology. No :REL/:SUPPORTS writes.
 */
public final class DefaultCanonicalRelationValidator implements CanonicalRelationValidator {

    public static final String STRATEGY_VERSION = "relation-validation-v1";
    private static final TypeReference<List<RelationCandidate>> RELATIONS = new TypeReference<>() {};

    private final ResolutionGraphLookup graphLookup;
    private final ExtractedMentionGraphReadPort extractedReadPort;
    private final RelationValidator syntology;
    private final IngestionCacheClient cacheClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultCanonicalRelationValidator(ResolutionGraphLookup graphLookup,
                                             ExtractedMentionGraphReadPort extractedReadPort,
                                             RelationValidator syntology,
                                             IngestionCacheClient cacheClient) {
        this.graphLookup = graphLookup;
        this.extractedReadPort = extractedReadPort;
        this.syntology = syntology;
        this.cacheClient = cacheClient;
    }

    @Override
    public RelationValidationResult validate(String tenantId, String ontologyVersion,
                                             String extractedRelationId) {
        Optional<ExtractedRelationView> found = extractedReadPort.getExtractedRelation(tenantId, extractedRelationId);
        if (found.isEmpty()) {
            return invalid("EXTRACTED_RELATION_NOT_FOUND",
                    "Extracted relation not found in tenant", null, null, null, null, null,
                    ontologyVersion, List.of());
        }
        ExtractedRelationView view = found.get();
        List<ActiveResolution> active = graphLookup.getActiveResolutions(tenantId,
                List.of(view.subjectMentionId(), view.objectMentionId()));
        Map<String, List<ActiveResolution>> byMention = new LinkedHashMap<>();
        for (ActiveResolution resolution : active) {
            byMention.computeIfAbsent(resolution.mentionId(), ignored -> new ArrayList<>()).add(resolution);
        }
        List<ActiveResolution> subjects = byMention.getOrDefault(view.subjectMentionId(), List.of());
        List<ActiveResolution> objects = byMention.getOrDefault(view.objectMentionId(), List.of());
        if (subjects.size() != 1 || objects.size() != 1) {
            String reason = subjects.isEmpty() || objects.isEmpty()
                    ? "MISSING_ACTIVE_RESOLUTION" : "MULTIPLE_ACTIVE_RESOLUTIONS";
            return invalid(reason,
                    "Both endpoints need exactly one active resolution",
                    subjects.isEmpty() ? null : subjects.getFirst().canonicalEntityId(),
                    view.predicate(),
                    objects.isEmpty() ? null : objects.getFirst().canonicalEntityId(),
                    subjects.isEmpty() ? null : subjects.getFirst().canonicalEntityType(),
                    objects.isEmpty() ? null : objects.getFirst().canonicalEntityType(),
                    ontologyVersion, List.of());
        }
        ActiveResolution subject = subjects.getFirst();
        ActiveResolution object = objects.getFirst();

        // Cassandra evidence must still contain this extracted relation/predicate.
        Optional<ExtractionResultRow> evidence = readEvidence(tenantId, view);
        if (evidence.isEmpty()) {
            return invalid("EXTRACTED_EVIDENCE_NOT_FOUND",
                    "Extraction evidence for the relation is missing", subject.canonicalEntityId(),
                    view.predicate(), object.canonicalEntityId(), subject.canonicalEntityType(),
                    object.canonicalEntityType(), ontologyVersion, List.of());
        }
        if (!evidenceContains(evidence.get(), view.predicate())) {
            return invalid("EXTRACTED_RELATION_NOT_FOUND",
                    "Evidence chunk no longer contains this relation", subject.canonicalEntityId(),
                    view.predicate(), object.canonicalEntityId(), subject.canonicalEntityType(),
                    object.canonicalEntityType(), ontologyVersion, List.of());
        }

        RelationValidation validation;
        try {
            validation = syntology.validate(tenantId, subject.canonicalEntityType(), view.predicate(),
                    object.canonicalEntityType(), ontologyVersion);
        } catch (RetryableProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RetryableProcessingException("Syntology relation validation unavailable", e);
        }

        List<ValidationViolation> violations = validation.violations().stream()
                .map(violation -> new ValidationViolation(violation.code(), violation.message(),
                        violation.path()))
                .toList();
        return validation.conformant()
                ? new RelationValidationResult(RelationValidationOutcome.VALID,
                subject.canonicalEntityId(), view.predicate(), object.canonicalEntityId(),
                subject.canonicalEntityType(), object.canonicalEntityType(), ontologyVersion,
                List.of(), "VALID")
                : new RelationValidationResult(RelationValidationOutcome.INVALID,
                subject.canonicalEntityId(), view.predicate(), object.canonicalEntityId(),
                subject.canonicalEntityType(), object.canonicalEntityType(), ontologyVersion,
                violations, "ONTOLOGY_INVALID");
    }

    private Optional<ExtractionResultRow> readEvidence(String tenantId, ExtractedRelationView view) {
        String chunkId = view.evidenceChunkId();
        int separator = chunkId == null ? -1 : chunkId.lastIndexOf(':');
        if (separator <= 0) {
            return Optional.empty();
        }
        UUID contentRef;
        int ordinal;
        try {
            contentRef = UUID.fromString(chunkId.substring(0, separator));
            ordinal = Integer.parseInt(chunkId.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return cacheClient.listExtractionResults(tenantId, contentRef).stream()
                .filter(row -> row.chunkOrdinal() == ordinal)
                .findFirst();
    }

    private boolean evidenceContains(ExtractionResultRow row, String predicate) {
        if (row.relationsJson() == null || row.relationsJson().isBlank()) {
            return false;
        }
        try {
            return mapper.readValue(row.relationsJson(), RELATIONS).stream()
                    .anyMatch(relation -> predicate.equals(relation.predicate()));
        } catch (Exception e) {
            throw new RetryableProcessingException("Could not read extraction evidence", e);
        }
    }

    private static RelationValidationResult invalid(String reason, String message,
                                                    String subjectEntityId, String predicate,
                                                    String objectEntityId, String subjectType,
                                                    String objectType, String ontologyVersion,
                                                    List<ValidationViolation> violations) {
        List<ValidationViolation> all = new ArrayList<>(violations);
        all.add(new ValidationViolation(reason, message, null));
        return new RelationValidationResult(RelationValidationOutcome.INVALID,
                subjectEntityId, predicate, objectEntityId, subjectType, objectType,
                ontologyVersion, all, reason);
    }
}
