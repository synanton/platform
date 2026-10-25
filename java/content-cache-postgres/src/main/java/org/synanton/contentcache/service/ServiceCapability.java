package org.synanton.contentcache.service;

/** Compiled service capabilities. A config file cannot add an entry. */
public enum ServiceCapability {
    CONTENT_RECONCILE_TENANT,
    CONTENT_RECONCILE_CROSS_TENANT,
    CONTENT_MUTATE_TENANT,
    MIGRATION_READ_TENANT,
    SEARCH_REINDEX_TENANT
}
