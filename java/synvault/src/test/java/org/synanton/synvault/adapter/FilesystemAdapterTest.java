package org.synanton.synvault.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.synvault.domain.ContentRef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemAdapterTest {

    private final FilesystemAdapter adapter = new FilesystemAdapter();

    @Test
    void descriptorHasFileScheme() {
        assertThat(adapter.descriptor().scheme()).isEqualTo("file");
    }

    @Test
    void listsTextAndMarkdownFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "hello");
        Files.writeString(dir.resolve("b.md"), "# title");
        Files.writeString(dir.resolve("c.bin"), "binary");  // .bin not in allowed list

        List<ContentRef> refs = adapter.list("file://" + dir).toList();
        assertThat(refs).hasSize(2);
        assertThat(refs).allMatch(r -> r.uri().endsWith(".txt") || r.uri().endsWith(".md"));
    }

    @Test
    void returnsEmptyStreamForNonExistentDirectory() {
        var refs = adapter.list("file:///does/not/exist/at/all").toList();
        assertThat(refs).isEmpty();
    }

    @Test
    void openReturnsFileContents(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.txt");
        Files.writeString(file, "hello synanton");

        var refs = adapter.list("file://" + dir).toList();
        assertThat(refs).hasSize(1);

        try (InputStream is = adapter.open(refs.get(0))) {
            String content = new String(is.readAllBytes());
            assertThat(content).isEqualTo("hello synanton");
        }
    }

    @Test
    void lastModifiedPresent(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("test.md"), "content");
        var refs = adapter.list("file://" + dir).toList();
        assertThat(adapter.lastModified(refs.get(0))).isPresent();
    }

    @Test
    void walksSubdirectories(@TempDir Path dir) throws IOException {
        Path sub = dir.resolve("sub");
        Files.createDirectory(sub);
        Files.writeString(dir.resolve("root.txt"), "root");
        Files.writeString(sub.resolve("nested.txt"), "nested");

        var refs = adapter.list("file://" + dir).toList();
        assertThat(refs).hasSize(2);
    }

    @Test
    void mimeTypeDetectedCorrectly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("doc.md"), "# heading");
        var refs = adapter.list("file://" + dir).toList();
        assertThat(refs.get(0).mimeType()).isEqualTo("text/markdown");
    }
}
