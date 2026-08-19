package org.synanton.synt.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "syntology")
public record SyntologyProperties(
        Storage storage,
        Cache cache,
        Mcp mcp,
        Tenant tenant,
        Events events,
        String ontologyDir,
        Auth auth,
        Schema schema
) {
    public record Storage(String adapter, Jena jena) {
        public record Jena(String path) {
        }
    }

    public record Cache(long entityTtlSeconds) {
    }

    public record Mcp(Server server) {
        public record Server(String path) {
        }
    }

    public record Tenant(String defaultId) {
    }

    public record Events(String logPath) {
    }

    public record Auth(boolean enabled, String jwtSecret) {
    }

    public record Schema(String gitRoot) {
    }
}
