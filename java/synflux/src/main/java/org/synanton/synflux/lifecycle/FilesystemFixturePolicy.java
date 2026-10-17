package org.synanton.synflux.lifecycle;

import org.synanton.synvault.domain.ContentRef;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Restricts the local filesystem connector to an explicitly configured fixture root. */
public final class FilesystemFixturePolicy {

    private final Path allowedRoot;

    public FilesystemFixturePolicy(Path allowedRoot) {
        this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
    }

    public Path validateRoot(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"file".equals(uri.getScheme())) {
                throw new IllegalArgumentException("Fixture source endpoint must use file://");
            }
            Path configuredRoot = allowedRoot.toRealPath();
            Path sourceRoot = Path.of(uri).toRealPath();
            if (!Files.isDirectory(sourceRoot) || !sourceRoot.startsWith(configuredRoot)) {
                throw new IllegalArgumentException("Fixture source endpoint must be a directory under SYNFLUX_FIXTURE_ROOT");
            }
            return sourceRoot;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid filesystem fixture endpoint", e);
        }
    }

    public String resourceId(String endpoint, ContentRef ref) {
        Path sourceRoot = validateRoot(endpoint);
        try {
            Path file = Path.of(URI.create(ref.uri())).toRealPath();
            if (!file.startsWith(sourceRoot)) {
                return "";
            }
            return sourceRoot.relativize(file).toString().replace('\\', '/');
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }
}
