package org.synanton.gpu.client;

/**
 * Supplies a short-lived GPU gateway execution JWT. T-K8S-6a owns the signer; this
 * interface is the seam so the client can attach the token without owning key material.
 */
@FunctionalInterface
public interface GpuPlaneJwtProvider {

    /** @return the bearer token, or null/blank when signing is not configured. */
    String token();
}
