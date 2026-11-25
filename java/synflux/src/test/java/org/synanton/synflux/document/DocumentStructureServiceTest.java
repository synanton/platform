package org.synanton.synflux.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStructureServiceTest {

    private final DocumentStructureService service = DocumentStructureService.defaults();

    @Test
    void markdownPreservesStructureOffsetsAndProvenance() {
        String markdown = """
            # Book Title

            A first paragraph with text.

            - item one
            - item two

            ## Section

            ```java
            int x = 1;
            ```
            """;

        DocumentStructure structure = service.process(source("text/markdown", markdown.getBytes(StandardCharsets.UTF_8)));

        assertThat(structure.pages()).isEmpty();
        assertThat(structure.parserName()).isEqualTo("Synanton Markdown Adapter");
        assertThat(structure.normalizationVersion()).isEqualTo("NFC_UTF16_V1");
        assertThat(structure.elements())
            .extracting(DocumentStructure.Element::elementType)
            .containsExactly(
                DocumentStructure.ElementType.HEADING,
                DocumentStructure.ElementType.PARAGRAPH,
                DocumentStructure.ElementType.LIST,
                DocumentStructure.ElementType.HEADING,
                DocumentStructure.ElementType.CODE);
        assertThat(structure.chunks()).hasSizeGreaterThanOrEqualTo(2);

        for (DocumentStructure.Element element : structure.elements()) {
            assertThat(element.geometryStatus()).isEqualTo(DocumentStructure.GeometryStatus.NOT_APPLICABLE);
            assertThat(element.boundingBox()).isNull();
            assertThat(element.pageNumber()).isNull();
            assertThat(structure.text().substring(element.documentStartOffset(), element.documentEndOffset()))
                .isEqualTo(element.text());
            DocumentStructure.Chunk chunk = chunk(structure, element.chunkId());
            assertThat(chunk.text().substring(element.chunkStartOffset(), element.chunkEndOffset()))
                .isEqualTo(element.text());
        }
        assertThat(structure.text()).contains("A first paragraph with text.");
        assertThat(structure.text()).contains("int x = 1;");
    }

    @Test
    void markdownIdentitiesAreDeterministic() {
        byte[] bytes = "# Title\n\nBody text.\n".getBytes(StandardCharsets.UTF_8);
        DocumentStructure first = service.process(source("text/markdown", bytes));
        DocumentStructure second = service.process(source("text/markdown", bytes));

        assertThat(first.elements()).extracting(DocumentStructure.Element::elementId)
            .containsExactlyElementsOf(second.elements().stream()
                .map(DocumentStructure.Element::elementId).toList());
        assertThat(first.chunks()).extracting(DocumentStructure.Chunk::chunkId)
            .containsExactlyElementsOf(second.chunks().stream()
                .map(DocumentStructure.Chunk::chunkId).toList());
    }

    @Test
    void pdfPreservesPagesGeometryAndOffsets() throws Exception {
        byte[] pdf = pdf("Alpha document", "Second line on page one", "Page two text");

        DocumentStructure structure = service.process(source("application/pdf", pdf));

        assertThat(structure.parserName()).isEqualTo("Apache PDFBox");
        assertThat(structure.pages()).hasSize(2);
        assertThat(structure.pages().get(0).coordinateSystem()).isEqualTo("PDF_TEXT_ADJUSTED_TOP_LEFT");
        assertThat(structure.pages().get(0).unit()).isEqualTo("POINT");
        assertThat(structure.chunks()).hasSize(2);
        assertThat(structure.elements()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(structure.elements()).extracting(DocumentStructure.Element::pageNumber)
            .contains(1, 2);
        assertThat(structure.text()).contains("Alpha document");

        for (DocumentStructure.Element element : structure.elements()) {
            assertThat(element.geometryStatus()).isEqualTo(DocumentStructure.GeometryStatus.AVAILABLE);
            assertThat(element.boundingBox()).isNotNull();
            assertThat(element.boundingBox().x0()).isGreaterThanOrEqualTo(0);
            assertThat(element.boundingBox().y0()).isGreaterThanOrEqualTo(0);
            assertThat(element.boundingBox().x1()).isLessThanOrEqualTo(612.5f);
            assertThat(element.boundingBox().y1()).isLessThanOrEqualTo(792.5f);
            assertThat(structure.text().substring(element.documentStartOffset(), element.documentEndOffset()))
                .isEqualTo(element.text());
        }
    }

    @Test
    void rejectsUnsupportedMediaType() {
        SourceDocument source = source("application/msword", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.process(source))
            .isInstanceOf(UnsupportedDocumentFormatException.class)
            .hasMessageContaining("application/msword");
    }

    @Test
    void normalizesMediaTypeParameters() {
        assertThat(DocumentStructureService.normalizeMediaType("text/markdown; charset=UTF-8"))
            .isEqualTo("text/markdown");
    }

    private static DocumentStructure.Chunk chunk(DocumentStructure structure, String chunkId) {
        return structure.chunks().stream()
            .filter(chunk -> chunk.chunkId().equals(chunkId))
            .findFirst()
            .orElseThrow();
    }

    private static SourceDocument source(String mediaType, byte[] bytes) {
        return new SourceDocument("tenant-a", "source-1", "version-1", "doc-1", mediaType, bytes);
    }

    private static byte[] pdf(String... lines) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText(lines[0]);
                content.endText();
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 700);
                content.showText(lines[1]);
                content.endText();
            }
            PDPage second = new PDPage();
            document.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(document, second)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText(lines[2]);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
