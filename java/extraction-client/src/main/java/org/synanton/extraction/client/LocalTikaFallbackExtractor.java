package org.synanton.extraction.client;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * In-process flat-text fallback used when the extraction plane is disabled or unreachable.
 */
public class LocalTikaFallbackExtractor {

    private static final Logger log = LoggerFactory.getLogger(LocalTikaFallbackExtractor.class);
    private static final Tika TIKA = new Tika();

    public FallbackExtractionResult extract(byte[] bytes, String sourceUri, boolean includeMetadata) {
        String flatText = extractFlatText(bytes, sourceUri);
        Map<String, String> metadata = includeMetadata ? extractMetadata(bytes, sourceUri) : Map.of();
        return new FallbackExtractionResult(flatText, metadata);
    }

    private static String extractFlatText(byte[] bytes, String sourceUri) {
        try {
            return TIKA.parseToString(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.warn("Flat text extraction failed for {}: {}", sourceUri, e.getMessage());
            return "";
        }
    }

    private static Map<String, String> extractMetadata(byte[] bytes, String sourceUri) {
        Map<String, String> meta = new HashMap<>();
        try {
            Metadata tikaMetadata = new Metadata();
            TIKA.parseToString(new ByteArrayInputStream(bytes), tikaMetadata);
            for (String name : tikaMetadata.names()) {
                meta.put(name, tikaMetadata.get(name));
            }
        } catch (Exception e) {
            log.warn("Metadata extraction failed for {}: {}", sourceUri, e.getMessage());
        }
        return meta;
    }
}
