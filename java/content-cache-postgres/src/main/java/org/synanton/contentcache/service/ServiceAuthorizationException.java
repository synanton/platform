package org.synanton.contentcache.service;

/** Explicit denial for a service mutation or an expired/invalid principal. */
public class ServiceAuthorizationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ServiceAuthorizationException(String message) {
        super(message);
    }
}
