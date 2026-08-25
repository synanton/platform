package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class ParseStage implements PipelineStage<AcquiredDocument, ParsedDocument> {

    private static final Logger log = LoggerFactory.getLogger(ParseStage.class);
    private static final Tika TIKA = new Tika();

    @Override
    public String name() { return "parse"; }

    @Override
    public ParsedDocument apply(AcquiredDocument doc, StageContext ctx) {
        String text = "";
        Map<String, String> meta = new HashMap<>();
        try {
            Metadata metadata = new Metadata();
            text = TIKA.parseToString(new ByteArrayInputStream(doc.bytes()), metadata);
            for (String name : metadata.names()) {
                meta.put(name, metadata.get(name));
            }
        } catch (Exception e) {
            log.warn("Parse failed for {}: {}", doc.sourceUri(), e.getMessage());
        }
        return new ParsedDocument(doc, text, meta, null);
    }
}
