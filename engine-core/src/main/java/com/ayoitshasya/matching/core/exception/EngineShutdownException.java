package com.ayoitshasya.matching.core.exception;

/**
 * Thrown when a command is submitted to a matching engine after its shutdown has begun. The
 * engine drains every command already queued before a shutdown started, but rejects anything
 * offered afterward outright rather than queuing it behind a writer thread that is about to
 * stop, which would otherwise leave the caller's future incomplete forever.
 */
public class EngineShutdownException extends RuntimeException {

    public EngineShutdownException(String message) {
        super(message);
    }
}
