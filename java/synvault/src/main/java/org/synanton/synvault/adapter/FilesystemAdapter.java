package org.synanton.synvault.adapter;

import org.synanton.synvault.domain.AdapterDescriptor;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.spi.ContentAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public class FilesystemAdapter implements ContentAdapter {

    private static final Logger log = LoggerFactory.getLogger(FilesystemAdapter.class);
    private static final int MAX_DEPTH = 32;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".txt", ".md", ".html", ".pdf", ".json", ".csv"
    );

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor("file", "Local Filesystem", "1.0");
    }

    @Override
    public Stream<ContentRef> list(String rootUri) {
        Path root;
        try {
            String path = rootUri.startsWith("file://") ? rootUri.substring(7) : rootUri;
            root = Path.of(path);
        } catch (Exception e) {
            log.warn("Cannot parse URI: {}", rootUri);
            return Stream.empty();
        }

        if (!Files.isDirectory(root)) {
            log.warn("Not a directory: {}", root);
            return Stream.empty();
        }

        List<ContentRef> refs = new ArrayList<>();
        try {
            Files.walkFileTree(root, Collections.emptySet(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    boolean allowed = ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
                    if (allowed && attrs.isRegularFile()) {
                        String mime = guessMime(name);
                        refs.add(new ContentRef(
                            "file",
                            file.toUri().toString(),
                            mime,
                            attrs.size(),
                            attrs.lastModifiedTime().toInstant()
                        ));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Cannot visit {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return refs.stream();
    }

    @Override
    public InputStream open(ContentRef ref) {
        try {
            return new FileInputStream(localPath(ref));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Instant> lastModified(ContentRef ref) {
        try {
            return Optional.of(Files.getLastModifiedTime(Path.of(localPath(ref))).toInstant());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * file:// URIs from {@code Path.toUri()} are percent-encoded (spaces,
     * {@code &}, non-ASCII); decode after stripping the scheme or every
     * such file 404s. Found via RAG-Multi-Corpus filenames, 2026-10-11.
     */
    private static String localPath(ContentRef ref) {
        String uri = ref.uri();
        String path = uri.startsWith("file://") ? uri.substring(7) : uri.replace("file:", "");
        try {
            return java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    private static String guessMime(String name) {
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }
}
