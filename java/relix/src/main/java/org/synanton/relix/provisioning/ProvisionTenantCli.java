package org.synanton.relix.provisioning;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.synanton.ingestioncache.client.IngestionCacheClient;

/** Command-line entry point for isolated evaluation tenant provisioning. */
public final class ProvisionTenantCli {
    private ProvisionTenantCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String tenant = required(opts, "tenant");
        String provider = required(opts, "provider");
        String ontologyVersion = opts.getOrDefault("ontology-version", "1.0.0");
        String ttlPath = opts.get("ontology-ttl");
        String syntology = opts.getOrDefault("syntology-url", "http://127.0.0.1:18080");
        String relix = opts.getOrDefault("relix-url", "http://127.0.0.1:18084");
        String contactPoints = opts.getOrDefault("cassandra-contact-points", "127.0.0.1:19042");
        String localDc = opts.getOrDefault("cassandra-local-dc", "datacenter1");
        String token = opts.getOrDefault("token", System.getenv("PROVISION_TOKEN"));
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Missing required --token or PROVISION_TOKEN");
        }
        String auditDir = opts.getOrDefault("audit-dir", "docs/acceptance/provisioning-audit");
        String operator = opts.getOrDefault("operator", System.getProperty("user.name", "unknown"));
        String gitSha = opts.getOrDefault("git-sha", "unknown");
        byte[] ttl = ttlPath == null || ttlPath.isBlank()
                ? new byte[0] : Files.readAllBytes(Path.of(ttlPath));

        var builder = CqlSession.builder().withLocalDatacenter(localDc);
        for (String point : contactPoints.split(",")) {
            String[] hostPort = point.strip().split(":", 2);
            int port = hostPort.length == 2 ? Integer.parseInt(hostPort[1]) : 9042;
            builder.addContactPoint(new InetSocketAddress(hostPort[0], port));
        }
        try (CqlSession session = builder.build()) {
            IngestionCacheClient cache = new IngestionCacheClient(session);
            TenantProvisioner provisioner = new TenantProvisioner(
                    new CassandraProvisioningStore(session, cache),
                    new HttpOntologyProvisioner(syntology, token),
                    new HttpGraphProvisioner(relix, token),
                    Clock.systemUTC(), Path.of(auditDir));
            ProvisioningResult result = provisioner.provision(new TenantProvisioningSpec(
                    tenant, provider, ontologyVersion, ttl, operator, gitSha));
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(result));
            if (!"SUCCESS".equals(result.outcome())) {
                System.exit(1);
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            opts.put(key, args[++i]);
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required --" + key);
        }
        return value;
    }
}
