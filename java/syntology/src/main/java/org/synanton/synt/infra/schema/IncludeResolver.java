package org.synanton.synt.infra.schema;

import org.springframework.stereotype.Component;
import org.synanton.synt.domain.model.schema.OntologySchemaIr;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class IncludeResolver {

    private final HclSchemaParser parser;

    public IncludeResolver(HclSchemaParser parser) {
        this.parser = parser;
    }

    public OntologySchemaIr resolve(Path bundleRoot, Path entryFile) {
        Path root = bundleRoot.toAbsolutePath().normalize();
        Path entry = entryFile.toAbsolutePath().normalize();
        ensureInside(root, entry);
        return resolve(root, entry, new LinkedHashSet<>());
    }

    private OntologySchemaIr resolve(Path bundleRoot, Path file, Set<Path> stack) {
        if (stack.contains(file)) {
            throw new IllegalArgumentException("Cyclic include detected: " + file);
        }
        stack.add(file);
        HclSchemaParser.ParsedHcl parsed = parser.parseFile(file);
        OntologySchemaIr merged = OntologySchemaIr.empty();
        Path parent = file.getParent() != null ? file.getParent() : bundleRoot;
        for (String includePath : parsed.includes()) {
            Path child = parent.resolve(includePath).toAbsolutePath().normalize();
            ensureInside(bundleRoot, child);
            merged = merged.merge(resolve(bundleRoot, child, stack));
        }
        stack.remove(file);
        return merged.merge(parsed.ir());
    }

    public static void ensureInside(Path bundleRoot, Path candidate) {
        if (!candidate.startsWith(bundleRoot)) {
            throw new IllegalArgumentException("Include path escapes bundle root: " + candidate);
        }
        if (!candidate.toFile().isFile()) {
            throw new IllegalArgumentException("Included HCL file not found: " + candidate);
        }
    }
}
