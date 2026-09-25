package org.synanton.gpu.client;

/**
 * Spaces calls evenly: at most {@code perMinute} acquisitions per minute (0 = unlimited).
 * Callers are served in the order they reserve a slot. The wait happens outside the lock.
 */
final class RequestPacer {

    private final long intervalNanos;
    private long nextSlot;

    RequestPacer(int perMinute) {
        this.intervalNanos = perMinute > 0 ? 60_000_000_000L / perMinute : 0;
    }

    /** @return the time waited, in milliseconds */
    long acquire() {
        if (intervalNanos == 0) {
            return 0;
        }
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GpuPlaneException("cancelled", "interrupted while pacing GPU-plane requests");
            }
        }
        return wait / 1_000_000;
    }
}
