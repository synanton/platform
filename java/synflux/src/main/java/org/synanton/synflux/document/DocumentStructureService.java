package org.synanton.synflux.document;

import org.synanton.synflux.document.adapter.MarkdownDocumentAdapter;
import org.synanton.synflux.document.adapter.PdfDocumentAdapter;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DocumentStructureService {
    private final List<DocumentFormatAdapter> adapters;

    public DocumentStructureService(List<DocumentFormatAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        if (this.adapters.isEmpty()) {
            throw new IllegalArgumentException("at least one document adapter is required");
        }
    }

    public static DocumentStructureService defaults() {
        return new DocumentStructureService(List.of(new PdfDocumentAdapter(), new MarkdownDocumentAdapter()));
    }

    public DocumentStructure process(SourceDocument source) {
        Objects.requireNonNull(source, "source");
        String mediaType = normalizeMediaType(source.mediaType());
        return adapters.stream()
            .filter(adapter -> adapter.supports(mediaType))
            .findFirst()
            .orElseThrow(() -> new UnsupportedDocumentFormatException(mediaType))
            .parse(source);
    }

    public static String normalizeMediaType(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        return mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }
}
