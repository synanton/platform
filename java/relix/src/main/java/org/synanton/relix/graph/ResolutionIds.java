package org.synanton.relix.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.synanton.common.resolution.ResolutionDecision;

/** Deterministic identity for one policy decision; replaying it is idempotent. */
public final class ResolutionIds {

    private ResolutionIds() {
    }

    public static String of(ResolutionDecision decision) {
        String identity = decision.tenantId() + "|" + decision.sourceVersionId() + "|"
                + decision.mentionId() + "|" + decision.strategyVersion() + "|"
                + decision.resolvedEntityId();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
