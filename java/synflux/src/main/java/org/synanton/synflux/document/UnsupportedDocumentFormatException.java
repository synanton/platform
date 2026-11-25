package org.synanton.synflux.document;

public class UnsupportedDocumentFormatException extends DocumentProcessingException {
    private static final long serialVersionUID = 1L;

    public UnsupportedDocumentFormatException(String mediaType) {
        super("No structural document adapter for media type: " + mediaType);
    }
}
