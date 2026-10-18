package org.synanton.synflux.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemFixturePolicyTest {

    @Test
    void contentCacheRootMustNotOverlapFixtureTree(@TempDir Path tempDirectory) throws Exception {
        Path fixtureRoot = Files.createDirectory(tempDirectory.resolve("fixture"));
        Path nestedCacheRoot = Files.createDirectory(fixtureRoot.resolve("cache"));
        FilesystemFixturePolicy policy = new FilesystemFixturePolicy(fixtureRoot);

        assertThatThrownBy(() -> policy.validateSeparateContentCacheRoot(nestedCacheRoot))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separateContentCacheRootIsAllowed(@TempDir Path tempDirectory) throws Exception {
        Path fixtureRoot = Files.createDirectory(tempDirectory.resolve("fixture"));
        Path contentCacheRoot = Files.createDirectory(tempDirectory.resolve("content-cache"));
        FilesystemFixturePolicy policy = new FilesystemFixturePolicy(fixtureRoot);

        policy.validateSeparateContentCacheRoot(contentCacheRoot);
    }
}
