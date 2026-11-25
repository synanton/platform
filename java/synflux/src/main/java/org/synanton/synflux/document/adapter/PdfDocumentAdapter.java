package org.synanton.synflux.document.adapter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Version;
import org.synanton.synflux.document.DocumentFormatAdapter;
import org.synanton.synflux.document.DocumentProcessingException;
import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.DocumentStructureBuilder;
import org.synanton.synflux.document.SourceDocument;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF adapter using PDFBox. Produces one element per extracted text line and one chunk per page.
 *
 * <p>Limitations are explicit: no OCR, table reconstruction, or figure extraction. Bounding boxes
 * use PDFBox adjusted text coordinates in points; the coordinate system is named in the page
 * metadata.</p>
 */
public final class PdfDocumentAdapter implements DocumentFormatAdapter {
    public static final String PARSER_NAME = "Apache PDFBox";
    public static final String COORDINATE_SYSTEM = "PDF_TEXT_ADJUSTED_TOP_LEFT";
    public static final String UNIT = "POINT";
    public static final String NORMALIZATION_VERSION = "NFC_COLLAPSE_WHITESPACE_UTF16_V1";

    private static final Set<String> MEDIA_TYPES = Set.of("application/pdf", "application/x-pdf");

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
        try (PDDocument pdf = PDDocument.load(source.bytes())) {
            List<DocumentStructure.Page> pages = pages(pdf);
            LineCollector collector = new LineCollector();
            collector.setSortByPosition(true);
            collector.getText(pdf);

            String parserVersion = Version.getVersion();
            DocumentStructureBuilder builder = new DocumentStructureBuilder(
                source, NORMALIZATION_VERSION, "pdf-page-v1", PARSER_NAME, parserVersion, pages);
            collector.lines.forEach(line -> builder.add(new DocumentStructureBuilder.Block(
                line.pageNumber(), DocumentStructure.ElementType.PARAGRAPH, line.text(),
                line.boundingBox(), DocumentStructure.GeometryStatus.AVAILABLE,
                "page-" + line.pageNumber(), "")));
            return builder.build();
        } catch (IOException e) {
            throw new DocumentProcessingException(
                "Failed to parse PDF document " + source.documentId(), e);
        }
    }

    private static List<DocumentStructure.Page> pages(PDDocument pdf) {
        List<DocumentStructure.Page> pages = new ArrayList<>(pdf.getNumberOfPages());
        for (int i = 0; i < pdf.getNumberOfPages(); i++) {
            PDPage page = pdf.getPage(i);
            PDRectangle box = page.getMediaBox();
            pages.add(new DocumentStructure.Page(
                i + 1, box.getWidth(), box.getHeight(), COORDINATE_SYSTEM, UNIT));
        }
        return pages;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
            .replaceAll("\\s+", " ")
            .trim();
    }

    private record Line(
        int pageNumber,
        String text,
        DocumentStructure.BoundingBox boundingBox
    ) {}

    private static final class LineCollector extends PDFTextStripper {
        private final List<Line> lines = new ArrayList<>();

        private LineCollector() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            String normalized = normalize(text);
            if (normalized.isEmpty() || positions.isEmpty()) {
                return;
            }
            float x0 = Float.MAX_VALUE;
            float y0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE;
            float y1 = -Float.MAX_VALUE;
            for (TextPosition position : positions) {
                x0 = Math.min(x0, position.getXDirAdj());
                y0 = Math.min(y0, position.getYDirAdj());
                x1 = Math.max(x1, position.getXDirAdj() + position.getWidthDirAdj());
                y1 = Math.max(y1, position.getYDirAdj() + position.getHeightDir());
            }
            lines.add(new Line(
                getCurrentPageNo(), normalized,
                new DocumentStructure.BoundingBox(x0, y0, x1, y1)));
        }
    }
}
