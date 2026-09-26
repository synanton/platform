package org.synanton.synquest.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YDB-POC-011 module-boundary guard (precursor to the ArchUnit rule in YDB-POC-012):
 * {@code synquest-api} may reference only the JDK and {@code storage-contract}.
 * No provider imports, no framework imports, and no reference to {@code synquest-api}.
 */
class ApiBoundaryTest {

    private static final List<String> FORBIDDEN =
            List.of(
                    "datastax",
                    "cassandra",
                    "cql",
                    "ydb",
                    "ycql",
                    "yql",
                    "ingestioncache",
                    "synanton/synvault",
                    "springframework",
                    "lucene",
                    "testcontainers");

    @Test
    void mainClassesRespectModuleBoundary() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainClassesDir())) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .forEach(p -> {
                        String lower = read(p).toLowerCase();
                        for (String marker : FORBIDDEN) {
                            if (lower.contains(marker)) {
                                violations.add(p.getFileName() + " references '" + marker + "'");
                            }
                        }
                    });
        }
        assertThat(violations).as("boundary violations in synquest-api").isEmpty();
    }

    private static Path mainClassesDir() {
        Path testClass =
                Paths.get(
                        java.net.URI.create(
                                ApiBoundaryTest.class
                                        .getResource(ApiBoundaryTest.class.getSimpleName() + ".class")
                                        .toString()));
        Path mainRoot =
                Paths.get(
                        testClass
                                .toString()
                                .replace(
                                        "classes"
                                                + java.io.File.separator
                                                + "java"
                                                + java.io.File.separator
                                                + "test",
                                        "classes"
                                                + java.io.File.separator
                                                + "java"
                                                + java.io.File.separator
                                                + "main"));
        if (Files.isDirectory(mainRoot.getParent())) {
            return mainRoot.getParent();
        }
        throw new IllegalStateException("main classes dir not found for " + testClass);
    }

    private static String read(Path classFile) {
        try {
            return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
