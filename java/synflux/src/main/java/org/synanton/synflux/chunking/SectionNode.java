package org.synanton.synflux.chunking;

import synanton.extraction.v1.DocumentElement;

import java.util.List;

/**
 * Internal tree node produced by DocumentStructureBuilder.
 * Each node represents one heading-delimited section with its direct elements
 * and any child subsections.
 */
public record SectionNode(
    String id,
    String heading,
    int headingLevel,
    List<String> sectionPath,
    List<DocumentElement> elements,
    List<SectionNode> children,
    int pageStart,
    int pageEnd
) {}
