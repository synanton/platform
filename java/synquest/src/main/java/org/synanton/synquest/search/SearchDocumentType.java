package org.synanton.synquest.search;

/** Lucene document families sharing one Directory; filters must always select one. */
public enum SearchDocumentType {
    CHUNK,
    ENTITY
}
