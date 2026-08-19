package org.synanton.synt.infra.schema;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SchemaBundleUnpacker {

    private SchemaBundleUnpacker() {
    }

    public static Path unpackZip(byte[] zipBytes) throws IOException {
        Path dest = Files.createTempDirectory("syntology-schema-");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = dest.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(dest)) {
                    throw new IllegalArgumentException("Zip entry escapes bundle root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(resolved.getParent());
                Files.copy(zip, resolved);
            }
        }
        return dest;
    }

    public static Path writeFiles(java.util.Map<String, byte[]> files) throws IOException {
        Path dest = Files.createTempDirectory("syntology-schema-");
        for (var file : files.entrySet()) {
            Path resolved = dest.resolve(file.getKey()).normalize();
            if (!resolved.startsWith(dest)) {
                throw new IllegalArgumentException("File name escapes bundle root: " + file.getKey());
            }
            Files.createDirectories(resolved.getParent());
            Files.write(resolved, file.getValue());
        }
        return dest;
    }
}
