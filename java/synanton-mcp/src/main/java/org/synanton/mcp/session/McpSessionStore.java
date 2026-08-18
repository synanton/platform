package org.synanton.mcp.session;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class McpSessionStore {

    public record Session(
            String sessionId,
            String subjectId,
            String tenantId,
            String tier,
            Set<String> scopes,
            Instant lastActiveAt,
            boolean terminated
    ) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public Session touch(String sessionId, String subjectId, String tenantId, String tier, Set<String> scopes) {
        Session session = new Session(sessionId, subjectId, tenantId, tier, scopes, Instant.now(), false);
        sessions.put(sessionId, session);
        return session;
    }

    public void terminate(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, session) -> new Session(
                session.sessionId(), session.subjectId(), session.tenantId(), session.tier(),
                session.scopes(), session.lastActiveAt(), true));
    }

    public Session get(String sessionId) {
        return sessions.get(sessionId);
    }
}
