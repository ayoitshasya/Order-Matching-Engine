package com.ayoitshasya.matching.core.exception;

/**
 * Thrown when a line read from an event log cannot be decoded back into a command: an unknown
 * entry type, a wrong number of fields, or a field that fails to parse. See
 * {@code EventLogReplayer} for how a truncated final line (the expected shape of a crash mid
 * write) is distinguished from this — a genuinely corrupt line anywhere else in the file.
 */
public class EventLogCorruptionException extends RuntimeException {

    public EventLogCorruptionException(String message) {
        super(message);
    }

    public EventLogCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
