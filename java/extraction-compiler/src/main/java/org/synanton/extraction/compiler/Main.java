package org.synanton.extraction.compiler;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** CLI for the deterministic IT Books extraction-contract compiler. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("compiler failed: " + e.getMessage());
            System.exit(2);
        }
    }

    static void run(String[] args) {
        Map<String, String> options = options(args);
        String baseUrl = options.getOrDefault("base-url",
            System.getenv().getOrDefault("SYNTOLOGY_URL", "http://localhost:8080"));
        String tenant = options.getOrDefault("tenant", "demo");
        String ontology = options.getOrDefault("ontology", "it-books");
        String release = options.get("release");
        String token = options.getOrDefault("token", System.getenv().getOrDefault("SYNANTON_TOKEN", ""));
        String output = options.getOrDefault("output", "build/p0");
        String compilerVersion = options.getOrDefault("compiler-version", OntologyCompiler.COMPILER_VERSION);
        String exportProfile = options.getOrDefault("export-profile", "it-books-v1-default");
        String schemaVersion = options.getOrDefault("schema-version", "1");
        String deployedImage = options.getOrDefault("deployed-image",
            System.getenv().getOrDefault("SYNANTON_DEPLOYED_IMAGE", ""));

        if (token.isBlank()) {
            throw new IllegalArgumentException("--token or SYNANTON_TOKEN is required");
        }

        SyntologyReleaseClient client = new SyntologyReleaseClient(baseUrl, tenant, token);
        ReleaseIdentity active = client.resolveActive(ontology);
        if (release != null && !release.isBlank() && !release.equals(active.version())) {
            throw new IllegalStateException("requested release " + release
                + " is not the active " + ontology + " release " + active.version());
        }
        var ir = client.fetchIr(active.version());
        var result = new OntologyCompiler().compile(new OntologyCompiler.CompileRequest(
            active, ir, compilerVersion, exportProfile, schemaVersion, deployedImage));
        Path outputDir = Path.of(output);
        result.writeTo(outputDir);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ontologyId", active.ontologyId());
        summary.put("version", active.version());
        summary.put("releaseId", active.releaseId());
        summary.put("releaseDigest", active.releaseDigest());
        summary.put("specDigest", result.specDigest());
        summary.put("templateDigest", result.templateDigest());
        summary.put("compilationId", result.compilationId());
        summary.put("output", outputDir.toAbsolutePath().toString());
        System.out.println(CanonicalJson.pretty(CanonicalJson.mapper().valueToTree(summary)));
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String name = arg.substring(2);
            if (name.isBlank()) {
                throw new IllegalArgumentException("empty option");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for --" + name);
            }
            options.put(name, args[++i]);
        }
        return options;
    }
}
