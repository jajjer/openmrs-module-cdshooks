package org.openmrs.module.cdshooks.client;

/**
 * Thrown when the terminology server (Snowstorm) is unreachable or returns a
 * 5xx error. Signals that the algorithm can't run — distinct from "concept
 * not found" (4xx), which is a legitimate no-data result.
 */
public class TerminologyUnavailableException extends RuntimeException {
    public TerminologyUnavailableException(String message) {
        super(message);
    }

    public TerminologyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
