package org.synanton.common.resolution;

/** Terminal: the source generation is newer/older than the persisted fence. */
public class StaleGenerationException extends ContractViolationException {

    private static final long serialVersionUID = 1L;

    public StaleGenerationException(String message) {
        super(message);
    }
}
