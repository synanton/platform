package org.synanton.ingestioncache.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SchemaInstaller {

    private static final Logger log = LoggerFactory.getLogger(SchemaInstaller.class);

    private static final String[] MIGRATIONS = {
        "cql/V1__baseline.cql",
        "cql/V2_1__kafka_outbox.cql",
        "cql/V3__chunk_provenance.cql",
        "cql/V4__ingest_usage.cql",
        "cql/V5__chunk_citation.cql",
        "cql/V6__chunk_classification.cql",
        "cql/V7__annotations.cql",
        "cql/V8__chunk_hierarchy.cql",
    };

    public static void install(CqlSession session) {
        log.info("Installing ingestion_cache schema...");
        for (String path : MIGRATIONS) {
            runScript(session, path);
        }
        log.info("ingestion_cache schema installed");
    }

    private static void runScript(CqlSession session, String path) {
        String cql;
        try {
            var resource = new ClassPathResource(path);
            cql = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("CQL migration not found, skipping: {}", path);
            return;
        }
        // Strip full-line `--` comments BEFORE splitting: a semicolon inside a
        // comment (e.g. V7's header) would otherwise split a statement mid-text and
        // silently skip the migration (YDB-POC-035 finding: annotations never applied).
        // Limitation: `--` inside string literals is not handled — none of the
        // bundled migrations contain any.
        String uncommented =
                Arrays.stream(cql.split("\n"))
                        .filter(line -> !line.trim().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"));
        Arrays.stream(uncommented.split(";"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .forEach(stmt -> {
                try {
                    session.execute(stmt + ";");
                } catch (Exception e) {
                    log.warn("CQL statement failed (may already exist): {}", e.getMessage());
                }
            });
    }
}
