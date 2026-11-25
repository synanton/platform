package org.synanton.synflux.document.adapter;

import org.synanton.synflux.document.DocumentFormatAdapter;
import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.DocumentStructureBuilder;
import org.synanton.synflux.document.SourceDocument;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Markdown adapter. It preserves heading structure and reading order; pages and bounding
 * boxes are inapplicable and explicitly marked.
 */
public final class MarkdownDocumentAdapter implements DocumentFormatAdapter {
    public static final String PARSER_NAME = "Synanton Markdown Adapter";
    public static final String PARSER_VERSION = "1";
    public static final String NORMALIZATION_VERSION = "NFC_UTF16_V1";

    private static final Set<String> MEDIA_TYPES = Set.of(
        "text/markdown", "text/x-markdown", "application/markdown");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+.*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    @Override
    public String name() {
        return PARSER_NAME;
    }

    @Override
    public boolean supports(String normalizedMediaType) {
        return MEDIA_TYPES.contains(normalizedMediaType);
    }

    @Override
    public DocumentStructure parse(SourceDocument source) {
        String markdown = new String(source.bytes(), StandardCharsets.UTF_8);
        String[] rawLines = Normalizer.normalize(markdown, Normalizer.Form.NFC)
            .replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        DocumentStructureBuilder builder = new DocumentStructureBuilder(
            source, NORMALIZATION_VERSION, "markdown-heading-v1", PARSER_NAME, PARSER_VERSION, List.of());
        Parser parser = new Parser(builder);
        for (String rawLine : rawLines) {
            parser.accept(rawLine);
        }
        parser.finish();
        return builder.build();
    }

    private static final class Parser {
        private final DocumentStructureBuilder builder;
        private final String[] sectionHeadings = new String[6];
        private final List<String> paragraph = new ArrayList<>();
        private final List<String> list = new ArrayList<>();
        private final List<String> table = new ArrayList<>();
        private final List<String> code = new ArrayList<>();
        private int chunkOrdinal = 0;
        private String chunkKey = "section-0";
        private String sectionPath = "";
        private boolean inCode;
        private String codeFence = "";

        private Parser(DocumentStructureBuilder builder) {
            this.builder = builder;
        }

        void accept(String rawLine) {
            String line = rawLine.stripTrailing();

            if (inCode) {
                if (line.strip().startsWith(codeFence)) {
                    add(DocumentStructure.ElementType.CODE, String.join("\n", code));
                    code.clear();
                    inCode = false;
                    codeFence = "";
                } else {
                    code.add(line);
                }
                return;
            }

            String stripped = line.strip();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                flushAll();
                inCode = true;
                codeFence = stripped.substring(0, 3);
                return;
            }
            if (stripped.isEmpty()) {
                flushAll();
                return;
            }

            Matcher heading = HEADING.matcher(stripped);
            if (heading.matches()) {
                flushAll();
                int level = heading.group(1).length();
                sectionHeadings[level - 1] = normalizeInline(heading.group(2));
                for (int i = level; i < sectionHeadings.length; i++) {
                    sectionHeadings[i] = null;
                }
                sectionPath = String.join(" > ", nonNull(sectionHeadings));
                chunkOrdinal++;
                chunkKey = "section-" + chunkOrdinal;
                add(DocumentStructure.ElementType.HEADING, sectionHeadings[level - 1]);
                return;
            }

            if (LIST_ITEM.matcher(line).matches()) {
                flushParagraph();
                flushTable();
                list.add(normalizeInline(stripped));
                return;
            }
            if (TABLE_ROW.matcher(line).matches()) {
                flushParagraph();
                flushList();
                table.add(stripped);
                return;
            }

            flushList();
            flushTable();
            paragraph.add(normalizeInline(stripped));
        }

        void finish() {
            if (inCode && !code.isEmpty()) {
                add(DocumentStructure.ElementType.CODE, String.join("\n", code));
            }
            flushAll();
        }

        private void flushAll() {
            flushParagraph();
            flushList();
            flushTable();
        }

        private void flushParagraph() {
            if (!paragraph.isEmpty()) {
                add(DocumentStructure.ElementType.PARAGRAPH, String.join(" ", paragraph));
                paragraph.clear();
            }
        }

        private void flushList() {
            if (!list.isEmpty()) {
                add(DocumentStructure.ElementType.LIST, String.join("\n", list));
                list.clear();
            }
        }

        private void flushTable() {
            if (!table.isEmpty()) {
                add(DocumentStructure.ElementType.TABLE, String.join("\n", table));
                table.clear();
            }
        }

        private void add(DocumentStructure.ElementType type, String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            builder.add(new DocumentStructureBuilder.Block(
                null, type, text, null, DocumentStructure.GeometryStatus.NOT_APPLICABLE,
                chunkKey, sectionPath));
        }

        private static String normalizeInline(String text) {
            return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replaceAll("[ \\t]+", " ")
                .trim();
        }

        private static List<String> nonNull(String[] values) {
            List<String> result = new ArrayList<>();
            for (String value : values) {
                if (value != null) {
                    result.add(value);
                }
            }
            return result;
        }
    }
}
