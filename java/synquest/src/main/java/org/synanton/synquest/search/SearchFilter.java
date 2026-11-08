package org.synanton.synquest.search;

/** Engine-neutral search scope (#550); tenant comes from the verified context. */
public record SearchFilter(String tenantId) {}
