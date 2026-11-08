package org.synanton.extraction.engine;

/** A unit of text handed to an extraction engine (typically one chunk). */
public record TextSegment(String chunkId, String text, int charOffsetStart) {}
