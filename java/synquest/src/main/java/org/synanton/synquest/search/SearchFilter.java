package org.synanton.synquest.search;

/** Engine-neutral search scope (#550); tenant comes from the verified context. */
public record SearchFilter(String tenantId, SearchDocumentType documentType) {

    public SearchFilter {
        if (documentType == null) {
            documentType = SearchDocumentType.CHUNK;
        }
    }

    /** Chunk search is the default for existing callers. */
    public SearchFilter(String tenantId) {
        this(tenantId, SearchDocumentType.CHUNK);
    }
}
