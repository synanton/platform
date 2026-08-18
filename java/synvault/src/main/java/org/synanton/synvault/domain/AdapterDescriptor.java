package org.synanton.synvault.domain;

public record AdapterDescriptor(
    String scheme,       // e.g. "file"
    String displayName,
    String version
) {}
