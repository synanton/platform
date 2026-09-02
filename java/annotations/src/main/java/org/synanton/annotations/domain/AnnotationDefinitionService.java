package org.synanton.annotations.domain;

import org.synanton.annotations.domain.model.AnnotationDefinition;
import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;
import org.synanton.annotations.domain.repository.AnnotationDefinitionRepository;
import org.synanton.annotations.domain.repository.AnnotationDefinitionVersionRepository;
import org.synanton.common.error.NotFoundException;
import org.synanton.common.error.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages annotation definition identity and the version lifecycle
 * (design §8, §72: {@code Draft -> Validated -> Published -> Deprecated -> Retired}).
 * A published version is immutable; changing its content requires registering a new version.
 */
public class AnnotationDefinitionService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final AnnotationDefinitionRepository definitions;
    private final AnnotationDefinitionVersionRepository versions;
    private final Clock clock;

    public AnnotationDefinitionService(
            AnnotationDefinitionRepository definitions,
            AnnotationDefinitionVersionRepository versions,
            Clock clock
    ) {
        this.definitions = definitions;
        this.versions = versions;
        this.clock = clock;
    }

    public AnnotationDefinition createDefinition(String definitionId, String namespace, String name, String annotationType) {
        if (!SLUG.matcher(definitionId).matches()) {
            throw new ValidationException("definitionId must be a lowercase-hyphen slug: " + definitionId);
        }
        if (!AnnotationDefinition.ANNOTATION_TYPES.contains(annotationType)) {
            throw new ValidationException("Unknown annotation_type: " + annotationType);
        }
        return definitions.insert(new AnnotationDefinition(definitionId, namespace, name, annotationType, Instant.now(clock)));
    }

    public AnnotationDefinition getDefinition(String definitionId) {
        return definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("Unknown definition: " + definitionId));
    }

    public AnnotationDefinitionVersion createVersion(
            String definitionId,
            int version,
            List<String> inputs,
            String producer,
            String producerVersion,
            String outputType,
            String outputName
    ) {
        definitions.findById(definitionId)
                .orElseThrow(() -> new NotFoundException("Unknown definition: " + definitionId));
        if (version < 1) {
            throw new ValidationException("version must be >= 1");
        }
        AnnotationDefinitionVersion draft = new AnnotationDefinitionVersion(
                definitionId, version, inputs, producer, producerVersion, outputType, outputName,
                AnnotationDefinitionVersion.DRAFT, null, Instant.now(clock));
        return versions.insert(draft);
    }

    public AnnotationDefinitionVersion getVersion(String definitionId, int version) {
        return versions.find(definitionId, version)
                .orElseThrow(() -> new NotFoundException("Unknown definition version: " + definitionId + "@" + version));
    }

    public List<AnnotationDefinitionVersion> listVersions(String definitionId) {
        return versions.findByDefinitionId(definitionId);
    }

    /** Overwrites content of a DRAFT/VALIDATED version. Rejected once PUBLISHED (design §8). */
    public AnnotationDefinitionVersion updateVersion(
            String definitionId,
            int version,
            List<String> inputs,
            String producer,
            String producerVersion,
            String outputType,
            String outputName
    ) {
        AnnotationDefinitionVersion current = getVersion(definitionId, version);
        if (!current.isMutable()) {
            throw new AlreadyPublishedException(
                    "Definition version " + definitionId + "@" + version + " is " + current.status() + " and immutable");
        }
        AnnotationDefinitionVersion updated = new AnnotationDefinitionVersion(
                definitionId, version, inputs, producer, producerVersion, outputType, outputName,
                current.status(), current.publishedAt(), current.createdAt());
        versions.updateContent(updated);
        return updated;
    }

    /** Publishes a DRAFT/VALIDATED version. A second publish call on an already-published version is rejected. */
    public AnnotationDefinitionVersion publish(String definitionId, int version) {
        AnnotationDefinitionVersion current = getVersion(definitionId, version);
        if (!current.isMutable()) {
            throw new AlreadyPublishedException(
                    "Definition version " + definitionId + "@" + version + " is already " + current.status());
        }
        Instant now = Instant.now(clock);
        versions.updateStatus(definitionId, version, AnnotationDefinitionVersion.PUBLISHED, now);
        return getVersion(definitionId, version);
    }

    public List<AnnotationDefinitionVersion> listPublished() {
        return versions.findAllPublished();
    }
}
