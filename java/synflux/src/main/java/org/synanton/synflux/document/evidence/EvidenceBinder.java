package org.synanton.synflux.document.evidence;

import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.StructuralArtifactDigest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * P3a deterministic evidence binder. It maps explicitly space-tagged candidates to the P1
 * structural artifact. It never calls an LLM and never assigns canonical identity.
 */
public final class EvidenceBinder {
    public static final String CONTRACT_VERSION = "p3a-evidence-binding-v1";
    public static final int BBOX_SCALE = 6;
    public static final RoundingMode BBOX_ROUNDING = RoundingMode.HALF_UP;

    public enum Space {
        STRUCTURAL_DOCUMENT, STRUCTURAL_CHUNK, EXTRACTION_INPUT
    }

    public enum Grounding {
        EXACT, APPROXIMATE, DOCUMENT_SCOPE
    }

    public enum Status {
        RESOLVED, UNMAPPED, MISBOUND
    }

    public record Candidate(
        String candidateId,
        Space space,
        int startOffset,
        int endOffset,
        String chunkId,
        Grounding grounding,
        String templateDigest,
        String compilationId
    ) {
        public Candidate {
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(space, "space");
            Objects.requireNonNull(grounding, "grounding");
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("invalid candidate offsets");
            }
            if (space == Space.STRUCTURAL_CHUNK
                && (chunkId == null || chunkId.isBlank())) {
                throw new IllegalArgumentException("STRUCTURAL_CHUNK requires chunkId");
            }
            if (grounding == Grounding.DOCUMENT_SCOPE
                && (startOffset != 0 || endOffset != 0)) {
                throw new IllegalArgumentException("DOCUMENT_SCOPE candidates carry no span offsets");
            }
        }
    }

    /** One extraction-input span mapped to one structural chunk span. */
    public record Segment(
        int inputStart,
        int inputEnd,
        String chunkId,
        int chunkStart,
        int chunkEnd,
        Grounding quality
    ) {
        public Segment {
            if (inputStart < 0 || inputEnd < inputStart || chunkStart < 0 || chunkEnd < chunkStart) {
                throw new IllegalArgumentException("invalid mapping segment offsets");
            }
            requireText(chunkId, "chunkId");
            Objects.requireNonNull(quality, "quality");
            if (quality == Grounding.DOCUMENT_SCOPE) {
                throw new IllegalArgumentException("mapping segments cannot be document-scope");
            }
        }
    }

    public record Mapping(
        String extractionTextSha256,
        String structuralArtifactDigest,
        String chunkingConfig,
        String templateDigest,
        String compilationId,
        List<Segment> segments
    ) {
        public Mapping {
            requireDigest(extractionTextSha256, "extractionTextSha256");
            requireDigest(structuralArtifactDigest, "structuralArtifactDigest");
            requireText(chunkingConfig, "chunkingConfig");
            Objects.requireNonNull(templateDigest, "templateDigest");
            Objects.requireNonNull(compilationId, "compilationId");
            List<Segment> sorted = new ArrayList<>(segments);
            sorted.sort(Comparator.comparingInt(Segment::inputStart));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).inputStart() < sorted.get(i - 1).inputEnd()) {
                    throw new IllegalArgumentException("mapping segments overlap");
                }
            }
            segments = List.copyOf(sorted);
        }

        public String digest() {
            StringBuilder canonical = new StringBuilder("extraction-mapping-v1\n");
            canonical.append("extractionTextSha256=").append(extractionTextSha256).append('\n')
                .append("structuralArtifactDigest=").append(structuralArtifactDigest).append('\n')
                .append("chunkingConfig=").append(chunkingConfig).append('\n')
                .append("templateDigest=").append(templateDigest).append('\n')
                .append("compilationId=").append(compilationId).append('\n');
            segments.stream()
                .sorted(Comparator.comparingInt(Segment::inputStart))
                .forEach(s -> canonical.append("segment=").append(s.inputStart()).append('|')
                    .append(s.inputEnd()).append('|').append(s.chunkId()).append('|')
                    .append(s.chunkStart()).append('|').append(s.chunkEnd()).append('|')
                    .append(s.quality()).append('\n'));
            return sha256(canonical.toString());
        }
    }

    /** Canonical fixed-scale decimal bbox; never a raw float. */
    public record CanonicalBox(String x0, String y0, String x1, String y1) {
        public CanonicalBox {
            requireText(x0, "x0");
            requireText(y0, "y0");
            requireText(x1, "x1");
            requireText(y1, "y1");
        }
    }

    public record Geometry(
        DocumentStructure.GeometryStatus status,
        Integer pageNumber,
        String coordinateSystem,
        String unit,
        CanonicalBox boundingBox
    ) {
        public Geometry {
            Objects.requireNonNull(status, "status");
            if (status == DocumentStructure.GeometryStatus.AVAILABLE && boundingBox == null) {
                throw new IllegalArgumentException("AVAILABLE geometry requires a bbox");
            }
            if (status != DocumentStructure.GeometryStatus.AVAILABLE && boundingBox != null) {
                throw new IllegalArgumentException("bbox requires AVAILABLE geometry");
            }
        }
    }

    public record Evidence(
        String candidateId,
        String contractVersion,
        Space inputSpace,
        Grounding inputGrounding,
        Grounding grounding,
        int inputStartOffset,
        int inputEndOffset,
        String documentId,
        String sourceVersionId,
        int documentStartOffset,
        int documentEndOffset,
        String text,
        String chunkId,
        List<String> chunkIds,
        List<String> elementIds,
        Geometry geometry,
        String parserName,
        String parserVersion,
        String normalizationVersion,
        String chunkingConfig,
        String structuralArtifactDigest,
        String mappingDigest,
        String templateDigest,
        String compilationId
    ) {
        public Evidence {
            requireText(candidateId, "candidateId");
            requireText(contractVersion, "contractVersion");
            Objects.requireNonNull(inputSpace, "inputSpace");
            Objects.requireNonNull(inputGrounding, "inputGrounding");
            Objects.requireNonNull(grounding, "grounding");
            chunkIds = List.copyOf(chunkIds);
            elementIds = List.copyOf(elementIds);
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    public record Result(Status status, Evidence evidence, String reason) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (status == Status.RESOLVED && evidence == null) {
                throw new IllegalArgumentException("RESOLVED requires evidence");
            }
            if (status != Status.RESOLVED && evidence != null) {
                throw new IllegalArgumentException("non-RESOLVED must not carry evidence");
            }
            reason = reason == null ? "" : reason;
        }
    }

    public record Report(
        int total,
        int resolved,
        int unmapped,
        int misbound,
        int exact,
        int approximate,
        int documentScope,
        int silentDrops,
        Map<String, Integer> failureReasons
    ) {
        public Report {
            failureReasons = Map.copyOf(failureReasons);
        }

        public static Report from(List<Result> results) {
            return from(results, results.size());
        }

        public static Report from(List<Result> results, int expectedCandidates) {
            if (expectedCandidates < 0) {
                throw new IllegalArgumentException("expectedCandidates must be >= 0");
            }
            int resolved = 0;
            int unmapped = 0;
            int misbound = 0;
            int exact = 0;
            int approximate = 0;
            int documentScope = 0;
            Map<String, Integer> reasons = new TreeMap<>();
            for (Result result : results) {
                switch (result.status()) {
                    case RESOLVED -> {
                        resolved++;
                        switch (result.evidence().grounding()) {
                            case EXACT -> exact++;
                            case APPROXIMATE -> approximate++;
                            case DOCUMENT_SCOPE -> documentScope++;
                        }
                    }
                    case UNMAPPED -> {
                        unmapped++;
                        reasons.merge(result.reason(), 1, Integer::sum);
                    }
                    case MISBOUND -> {
                        misbound++;
                        reasons.merge(result.reason(), 1, Integer::sum);
                    }
                }
            }
            int accounted = results.size();
            int silentDrops = Math.max(0, expectedCandidates - accounted);
            int total = Math.max(expectedCandidates, accounted);
            return new Report(total, resolved, unmapped, misbound, exact,
                approximate, documentScope, silentDrops, reasons);
        }

        public double misbindingRate() {
            return total == 0 ? 0.0 : (double) misbound / total;
        }

        public double silentDropRate() {
            return total == 0 ? 0.0 : (double) silentDrops / total;
        }
    }

    public Result bind(DocumentStructure structure, String extractionText, Mapping mapping,
                       Candidate candidate) {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(extractionText, "extractionText");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(candidate, "candidate");

        if (!StructuralArtifactDigest.sha256(structure).equals(mapping.structuralArtifactDigest())) {
            return misbound(candidate, "STRUCTURAL_ARTIFACT_MISMATCH");
        }
        if (!sha256(extractionText).equals(mapping.extractionTextSha256())) {
            return misbound(candidate, "EXTRACTION_TEXT_MISMATCH");
        }
        if (!matches(candidate.templateDigest(), mapping.templateDigest())) {
            return misbound(candidate, "TEMPLATE_DIGEST_MISMATCH");
        }
        if (!matches(candidate.compilationId(), mapping.compilationId())) {
            return misbound(candidate, "COMPILATION_ID_MISMATCH");
        }

        if (candidate.grounding() == Grounding.DOCUMENT_SCOPE) {
            return documentScope(structure, mapping, candidate);
        }
        if (candidate.space() == Space.EXTRACTION_INPUT) {
            return bindExtractionInput(structure, extractionText, mapping, candidate);
        }
        if (candidate.space() == Space.STRUCTURAL_DOCUMENT) {
            return resolveStructuralRange(structure, mapping, candidate,
                candidate.startOffset(), candidate.endOffset(), candidate.grounding(), "");
        }
        return bindStructuralChunk(structure, mapping, candidate);
    }

    public static CanonicalBox canonicalBox(DocumentStructure.BoundingBox box) {
        return new CanonicalBox(
            canonicalFloat(box.x0()), canonicalFloat(box.y0()),
            canonicalFloat(box.x1()), canonicalFloat(box.y1()));
    }

    static String canonicalFloat(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("bbox values must be finite");
        }
        if (value == 0.0f) {
            return BigDecimal.ZERO.setScale(BBOX_SCALE, BBOX_ROUNDING).toPlainString();
        }
        return new BigDecimal(Float.toString(value))
            .setScale(BBOX_SCALE, BBOX_ROUNDING).toPlainString();
    }

    private Result bindStructuralChunk(DocumentStructure structure, Mapping mapping,
                                       Candidate candidate) {
        DocumentStructure.Chunk chunk = structure.chunks().stream()
            .filter(c -> c.chunkId().equals(candidate.chunkId()))
            .findFirst().orElse(null);
        if (chunk == null) {
            return misbound(candidate, "UNKNOWN_CHUNK");
        }
        if (candidate.endOffset() > chunk.text().length()) {
            return misbound(candidate, "CHUNK_SPAN_OUT_OF_RANGE");
        }
        int documentStart = chunk.documentStartOffset() + candidate.startOffset();
        int documentEnd = chunk.documentStartOffset() + candidate.endOffset();
        return resolveStructuralRange(structure, mapping, candidate, documentStart, documentEnd,
            candidate.grounding(), "");
    }

    private Result bindExtractionInput(DocumentStructure structure, String extractionText,
                                       Mapping mapping, Candidate candidate) {
        if (candidate.endOffset() > extractionText.length()) {
            return misbound(candidate, "EXTRACTION_SPAN_OUT_OF_RANGE");
        }
        List<Span> spans = new ArrayList<>();
        int coveredUntil = candidate.startOffset();
        Grounding quality = candidate.grounding();
        for (Segment segment : mapping.segments()) {
            int start = Math.max(candidate.startOffset(), segment.inputStart());
            int end = Math.min(candidate.endOffset(), segment.inputEnd());
            if (start >= end) {
                continue;
            }
            if (start > coveredUntil) {
                return unmapped(candidate, "PARTIAL_MAPPING");
            }
            coveredUntil = Math.max(coveredUntil, end);
            DocumentStructure.Chunk chunk = chunk(structure, segment.chunkId());
            if (chunk == null) {
                return misbound(candidate, "UNKNOWN_MAPPING_CHUNK");
            }
            int chunkStart = segment.chunkStart() + (start - segment.inputStart());
            int chunkEnd = segment.chunkStart() + (end - segment.inputStart());
            if (chunkStart < 0 || chunkEnd > chunk.text().length()) {
                return misbound(candidate, "MAPPING_CHUNK_OUT_OF_RANGE");
            }
            spans.add(new Span(chunk.documentStartOffset() + chunkStart,
                chunk.documentStartOffset() + chunkEnd));
            if (segment.quality() == Grounding.APPROXIMATE) {
                quality = Grounding.APPROXIMATE;
            }
        }
        if (spans.isEmpty()) {
            return unmapped(candidate, "NO_MAPPING_SEGMENT");
        }
        if (coveredUntil < candidate.endOffset()) {
            return unmapped(candidate, "PARTIAL_MAPPING");
        }
        spans.sort(Comparator.comparingInt(Span::start));
        int start = spans.getFirst().start();
        int end = spans.getLast().end();
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i).start() != spans.get(i - 1).end()) {
                return misbound(candidate, "NON_CONTIGUOUS_MAPPING");
            }
        }
        return resolveStructuralRange(structure, mapping, candidate, start, end, quality,
            mapping.digest());
    }

    private Result resolveStructuralRange(DocumentStructure structure, Mapping mapping,
                                          Candidate candidate, int start, int end,
                                          Grounding grounding, String mappingDigest) {
        if (start < 0 || end <= start || end > structure.text().length()) {
            return misbound(candidate, "STRUCTURAL_SPAN_OUT_OF_RANGE");
        }
        List<DocumentStructure.Chunk> chunks = structure.chunks().stream()
            .filter(c -> c.documentStartOffset() < end && c.documentEndOffset() > start)
            .sorted(Comparator.comparingInt(DocumentStructure.Chunk::documentStartOffset))
            .toList();
        List<DocumentStructure.Element> elements = structure.elements().stream()
            .filter(e -> e.documentStartOffset() < end && e.documentEndOffset() > start)
            .sorted(Comparator.comparingInt(DocumentStructure.Element::documentStartOffset))
            .toList();
        if (chunks.isEmpty() || elements.isEmpty()) {
            return misbound(candidate, "NO_STRUCTURAL_ELEMENT");
        }
        Geometry geometry;
        try {
            geometry = geometry(structure, elements);
        } catch (IllegalArgumentException e) {
            return misbound(candidate, "BBOX_NOT_CANONICAL");
        }
        if (geometry == null) {
            return misbound(candidate, "GEOMETRY_PROVENANCE_MISSING");
        }
        Evidence evidence = new Evidence(
            candidate.candidateId(), CONTRACT_VERSION, candidate.space(), candidate.grounding(),
            grounding, candidate.startOffset(), candidate.endOffset(), structure.documentId(),
            structure.sourceVersionId(), start, end, structure.text().substring(start, end),
            chunks.getFirst().chunkId(), chunks.stream().map(DocumentStructure.Chunk::chunkId).toList(),
            elements.stream().map(DocumentStructure.Element::elementId).toList(), geometry,
            structure.parserName(), structure.parserVersion(), structure.normalizationVersion(),
            structure.chunkingConfig(), mapping.structuralArtifactDigest(), mappingDigest,
            mapping.templateDigest(), mapping.compilationId());
        return new Result(Status.RESOLVED, evidence, "");
    }

    private Result documentScope(DocumentStructure structure, Mapping mapping, Candidate candidate) {
        Geometry geometry = new Geometry(DocumentStructure.GeometryStatus.NOT_APPLICABLE,
            null, "", "", null);
        Evidence evidence = new Evidence(
            candidate.candidateId(), CONTRACT_VERSION, candidate.space(), candidate.grounding(),
            Grounding.DOCUMENT_SCOPE, 0, 0, structure.documentId(), structure.sourceVersionId(),
            0, structure.text().length(), structure.text(), "", List.of(), List.of(), geometry,
            structure.parserName(), structure.parserVersion(), structure.normalizationVersion(),
            structure.chunkingConfig(), mapping.structuralArtifactDigest(), mapping.digest(),
            mapping.templateDigest(), mapping.compilationId());
        return new Result(Status.RESOLVED, evidence, "");
    }

    private static Geometry geometry(DocumentStructure structure,
                                     List<DocumentStructure.Element> elements) {
        if (elements.stream().allMatch(e -> e.geometryStatus() == DocumentStructure.GeometryStatus.NOT_APPLICABLE)) {
            return new Geometry(DocumentStructure.GeometryStatus.NOT_APPLICABLE, null, "", "", null);
        }
        if (elements.size() == 1) {
            DocumentStructure.Element element = elements.getFirst();
            if (element.geometryStatus() == DocumentStructure.GeometryStatus.AVAILABLE) {
                DocumentStructure.Page page = page(structure, element.pageNumber());
                if (page == null) {
                    return null;
                }
                return new Geometry(DocumentStructure.GeometryStatus.AVAILABLE, element.pageNumber(),
                    page.coordinateSystem(), page.unit(), canonicalBox(element.boundingBox()));
            }
            return new Geometry(DocumentStructure.GeometryStatus.UNAVAILABLE, element.pageNumber(),
                "", "", null);
        }
        if (elements.stream().allMatch(e -> e.geometryStatus() == DocumentStructure.GeometryStatus.AVAILABLE)) {
            Integer pageNumber = elements.getFirst().pageNumber();
            if (pageNumber == null || elements.stream().anyMatch(e -> !pageNumber.equals(e.pageNumber()))) {
                return new Geometry(DocumentStructure.GeometryStatus.UNAVAILABLE, null, "", "", null);
            }
            DocumentStructure.Page page = page(structure, pageNumber);
            if (page == null) {
                return null;
            }
            float x0 = Float.MAX_VALUE;
            float y0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE;
            float y1 = -Float.MAX_VALUE;
            for (DocumentStructure.Element element : elements) {
                x0 = Math.min(x0, element.boundingBox().x0());
                y0 = Math.min(y0, element.boundingBox().y0());
                x1 = Math.max(x1, element.boundingBox().x1());
                y1 = Math.max(y1, element.boundingBox().y1());
            }
            return new Geometry(DocumentStructure.GeometryStatus.AVAILABLE, pageNumber,
                page.coordinateSystem(), page.unit(),
                canonicalBox(new DocumentStructure.BoundingBox(x0, y0, x1, y1)));
        }
        return new Geometry(DocumentStructure.GeometryStatus.UNAVAILABLE, null, "", "", null);
    }

    private static DocumentStructure.Page page(DocumentStructure structure, Integer pageNumber) {
        if (pageNumber == null) {
            return null;
        }
        return structure.pages().stream()
            .filter(p -> p.pageNumber() == pageNumber)
            .findFirst().orElse(null);
    }

    private static DocumentStructure.Chunk chunk(DocumentStructure structure, String chunkId) {
        return structure.chunks().stream().filter(c -> c.chunkId().equals(chunkId))
            .findFirst().orElse(null);
    }

    private static Result misbound(Candidate candidate, String reason) {
        return new Result(Status.MISBOUND, null, reason);
    }

    private static Result unmapped(Candidate candidate, String reason) {
        return new Result(Status.UNMAPPED, null, reason);
    }

    private static boolean matches(String candidateValue, String mappingValue) {
        return candidateValue == null || candidateValue.isBlank()
            || candidateValue.equals(mappingValue);
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Span(int start, int end) {
    }
}
