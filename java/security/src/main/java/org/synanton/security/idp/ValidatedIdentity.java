package org.synanton.security.idp;

public record ValidatedIdentity(
        String subjectId,
        String tenantId,
        String identityProfile,
        String[] scopes,
        String keyId
) {}
