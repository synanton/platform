package org.synanton.synflux.document;

public interface DocumentFormatAdapter {
    /** Stable adapter identity used for diagnostics. */
    String name();

    boolean supports(String normalizedMediaType);

    DocumentStructure parse(SourceDocument source);
}
