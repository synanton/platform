package org.synanton.synvault.domain;

import java.util.UUID;

public record ContentPushResult(UUID contentRefId, String sha256, long sizeBytes) {
}
