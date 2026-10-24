package org.synanton.contentcache.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Source-level facade boundary. The primary guard is compile-time (the
 * repository is package-private); this test keeps the source shape from
 * drifting when someone edits the declaration or adds a direct caller.
 */
class FacadeBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path PACKAGE = MAIN.resolve("org/synanton/contentcache/postgres");

    @Test
    void repositoryDeclarationStaysPackagePrivate() throws Exception {
        String source = Files.readString(PACKAGE.resolve("JdbcContentStore.java"));
        assertThat(source)
                .doesNotContain("public final class JdbcContentStore")
                .doesNotContain("public class JdbcContentStore");
    }

    @Test
    void mainSourcesReferenceTheRepositoryOnlyFromTheFacade() throws Exception {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(MAIN)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
        }
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.equals("JdbcContentStore.java") || name.equals("TenantScopedContentStore.java")) {
                continue;
            }
            if (Files.readString(file).contains("JdbcContentStore")) {
                offenders.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(offenders).as("direct repository use outside the facade").isEmpty();
    }
}
