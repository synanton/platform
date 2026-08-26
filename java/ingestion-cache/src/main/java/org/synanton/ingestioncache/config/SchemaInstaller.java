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
        Arrays.stream(cql.split(";"))
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
