package org.synanton.storage.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YDB-POC-037 provider-isolation guard: no class in this module may reference
 * provider-specific packages. Until ArchUnit lands in the build (YDB-POC-012),
 * this constant-pool scan enforces the same rule with plain JUnit.
 *
 * <p>Full cross-module rule ({@code synvault-api} must not depend on
 * {@code synquest-api} and vice versa) is added in YDB-POC-011 once those modules exist.
 */
class ProviderIsolationTest {

    private static final List<String> FORBIDDEN_MARKERS =
            List.of("datastax", "cassandra", "cql", "ydb", "ycql", "yql");

    @Test
    void noClassReferencesProviderPackages() throws IOException {
        Path classesDir = classesDir();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classesDir)) {
            files.filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .forEach(p -> {
                        String pool = readPool(p);
                        String lower = pool.toLowerCase();
                        for (String marker : FORBIDDEN_MARKERS) {
                            if (lower.contains(marker)) {
                                violations.add(p.getFileName() + " references '" + marker + "'");
                            }
                        }
                    });
        }
        assertThat(violations).as("provider references in storage-contract").isEmpty();
    }

    @Test
    void packageIsSingleSourceOfTruth() {
        assertThat(PackageInfo.PACKAGE).isEqualTo(ContractTypesTest.class.getPackageName());
    }

    private static Path classesDir() {
        String resource =
                ProviderIsolationTest.class.getResource(ProviderIsolationTest.class.getSimpleName() + ".class").toString();
        // file:.../build/classes/java/test/org/.../ProviderIsolationTest.class
        // -> sibling build/classes/java/main/org/... .
        Path testClass = Paths.get(java.net.URI.create(resource));
        Path testRoot = testClass.getParent(); // .../contract
        Path mainRoot =
                Paths.get(testRoot.toString().replace(
                        "classes" + java.io.File.separator + "java" + java.io.File.separator + "test",
                        "classes" + java.io.File.separator + "java" + java.io.File.separator + "main"));
        if (Files.isDirectory(mainRoot)) {
            return mainRoot;
        }
        return testRoot;
    }

    private static String readPool(Path classFile) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            // Scan raw constant-pool bytes as latin-1; markers are ASCII so this is exact.
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
