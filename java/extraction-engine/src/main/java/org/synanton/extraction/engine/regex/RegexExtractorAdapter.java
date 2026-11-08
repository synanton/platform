package org.synanton.extraction.engine.regex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;

/**
 * Phase A baseline adapter: deterministic, auditable regex extraction. It is
 * deliberately generic (identifiers, metrics, quoted phrases, capitalized
 * terms) and emits candidate mentions/relations only. Resolution and validation
 * are downstream. Expected to beat the previous 0.000 keyword-only baseline, not
 * to rival the LLM adapter.
 */
public final class RegexExtractorAdapter implements ExtractionEngineAdapter {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "\\b(?:ERR_[A-Z0-9_]+|[A-Z]{2,}[-_]\\d+[A-Za-z0-9-]*|v?\\d+\\.\\d+(?:\\.\\d+)?"
                    + "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b");
    private static final Pattern METRIC = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s?%");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{3,80})\"");
    private static final Pattern CAPITALIZED = Pattern.compile(
            "\\b[A-Z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*(?:\\s+[A-Z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*)*\\b");
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern CUE = Pattern.compile(
            "(?i)\\b(requires|uses|has|includes|contains|is|are|performs|logs|verifies|replaces|records|"
                    + "signs|combines|implements|obeys|measures|computes|visits|seeks|matches|applied to|"
                    + "part of|discussed in|improves over)\\b");

    private static final Set<String> STOPWORDS = Set.of(
            "The", "This", "That", "These", "Those", "A", "An", "In", "On", "For", "With", "Only",
            "And", "But", "If", "It", "We", "You", "They", "Chapter", "Section", "Page", "Figure",
            "Table", "First", "Second", "Third", "Fourth", "Fifth", "Typical", "Most", "Why", "After",
            "Before", "Because", "Then", "When", "Where", "Which", "What", "How", "During", "Between",
            "Among", "Under", "Over", "Each", "Every", "Some", "Many", "Several", "Other", "Such",
            "More", "Less", "Not", "No", "Yes", "I", "He", "She", "His", "Her", "Its", "Their", "Our",
            "Your", "My", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten");

    private record Candidate(int start, int end, String type) {}

    @Override
    public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
        String text = segment.text() == null ? "" : segment.text();
        int base = segment.charOffsetStart();

        Map<String, Candidate> unique = new LinkedHashMap<>();
        collect(IDENTIFIER, "Identifier", text, unique);
        collect(METRIC, "Metric", text, unique);
        collectQuoted(text, unique);
        collectCapitalized(text, unique);

        List<PreEntity> entities = new ArrayList<>();
        Map<String, PreEntity> bySpan = new LinkedHashMap<>();
        for (Candidate candidate : unique.values()) {
            if (!context.allowedEntityTypes().isEmpty()
                    && !context.allowedEntityTypes().contains(candidate.type())) {
                continue;
            }
            String mentionId = UUID.nameUUIDFromBytes(
                    (segment.chunkId() + "|" + candidate.start() + "|" + candidate.end())
                            .getBytes(StandardCharsets.UTF_8)).toString();
            PreEntity entity = new PreEntity(
                    mentionId,
                    text.substring(candidate.start(), candidate.end()),
                    candidate.type(),
                    base + candidate.start(),
                    base + candidate.end(),
                    sentenceContaining(text, candidate.start()));
            entities.add(entity);
            bySpan.put(candidate.start() + ":" + candidate.end(), entity);
        }

        List<RelationCandidate> relations = relations(segment, text, base, new ArrayList<>(entities));
        return new ExtractionResult(entities, relations);
    }

    private static void collect(Pattern pattern, String type, String text, Map<String, Candidate> unique) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            add(unique, matcher.start(), matcher.end(), type);
        }
    }

    private static void collectQuoted(String text, Map<String, Candidate> unique) {
        Matcher matcher = QUOTED.matcher(text);
        while (matcher.find()) {
            add(unique, matcher.start(1), matcher.end(1), "Concept");
        }
    }

    private static void collectCapitalized(String text, Map<String, Candidate> unique) {
        Matcher matcher = CAPITALIZED.matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group();
            int phraseStart = matcher.start();
            List<int[]> tokens = new ArrayList<>();
            List<String> tokenText = new ArrayList<>();
            Matcher tokenMatcher = TOKEN.matcher(phrase);
            while (tokenMatcher.find()) {
                tokens.add(new int[]{phraseStart + tokenMatcher.start(), phraseStart + tokenMatcher.end()});
                tokenText.add(tokenMatcher.group());
            }
            if (tokens.isEmpty()) {
                continue;
            }
            add(unique, tokens.getFirst()[0], tokens.getLast()[1], "Concept");
            for (int i = 0; i + 1 < tokens.size(); i++) {
                add(unique, tokens.get(i)[0], tokens.get(i + 1)[1], "Concept");
            }
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokenText.get(i);
                if (token.indexOf('-') >= 0 && token.length() > 2) {
                    add(unique, tokens.get(i)[0], tokens.get(i)[1], "Concept");
                } else if (tokens.size() == 1 && !STOPWORDS.contains(token) && token.length() >= 3) {
                    add(unique, tokens.get(i)[0], tokens.get(i)[1], "Concept");
                }
            }
        }
    }

    private static void add(Map<String, Candidate> unique, int start, int end, String type) {
        if (end - start < 2) {
            return;
        }
        unique.putIfAbsent(start + ":" + end + ":" + type, new Candidate(start, end, type));
    }

    private static List<RelationCandidate> relations(
            TextSegment segment, String text, int base, List<PreEntity> entities) {
        List<RelationCandidate> out = new ArrayList<>();
        int cursor = 0;
        for (String sentence : SENTENCE.split(text)) {
            int sentenceStart = text.indexOf(sentence, cursor);
            if (sentenceStart < 0) {
                sentenceStart = cursor;
            }
            int sentenceEnd = sentenceStart + sentence.length();
            cursor = sentenceEnd;
            Matcher cue = CUE.matcher(sentence);
            int emitted = 0;
            while (cue.find() && emitted < 20) {
                int cueStart = sentenceStart + cue.start();
                int cueEnd = sentenceStart + cue.end();
                PreEntity subject = nearest(entities, cueStart, true);
                PreEntity object = nearest(entities, cueEnd, false);
                if (subject == null || object == null || subject.mentionId().equals(object.mentionId())) {
                    continue;
                }
                out.add(new RelationCandidate(
                        subject.mentionId(),
                        predicate(cue.group(1)),
                        object.mentionId(),
                        segment.chunkId(),
                        base + sentenceStart,
                        base + sentenceEnd));
                emitted++;
            }
        }
        return out;
    }

    private static PreEntity nearest(List<PreEntity> entities, int offset, boolean before) {
        PreEntity best = null;
        for (PreEntity entity : entities) {
            if (before && entity.charEnd() <= offset) {
                if (best == null || entity.charEnd() > best.charEnd()) {
                    best = entity;
                }
            } else if (!before && entity.charStart() >= offset) {
                if (best == null || entity.charStart() < best.charStart()) {
                    best = entity;
                }
            }
        }
        return best;
    }

    private static String predicate(String cue) {
        String normalized = cue.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", "_");
        return switch (normalized) {
            case "is", "are" -> "is_a";
            default -> normalized;
        };
    }

    private static String sentenceContaining(String text, int offset) {
        int previous = text.lastIndexOf(". ", offset);
        int start = previous < 0 ? 0 : previous + 2;
        int end = text.indexOf(". ", offset);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, Math.min(end + 1, text.length())).trim();
    }
}
