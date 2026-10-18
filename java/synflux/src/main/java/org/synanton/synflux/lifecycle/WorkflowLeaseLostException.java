package org.synanton.synflux.lifecycle;

import java.util.concurrent.CancellationException;

/**
 * Thrown when a workflow worker loses its lease (renewal failure after GC pause,
 * network partition, or slow heartbeat). A lease-less worker must stop before its
 * next side effect: a dead worker can do no harm, but an alive and lease-less one
 * can — takeover by another worker is only safe if the prior holder observes the
 * loss and halts.
 *
 * <p>Extends {@link java.util.concurrent.CancellationException} so it propagates
 * through cancellation-tolerant paths (the lifecycle service rethrows it rather
 * than recording a terminal disposition — the workflow stays non-terminal and
 * is resumed by the new leaseholder).
 */
public class WorkflowLeaseLostException extends CancellationException {

    public WorkflowLeaseLostException() {
        super("Workflow lease lost — stopping before the next side effect");
    }
}
