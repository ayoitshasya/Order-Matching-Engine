package com.ayoitshasya.matching.core.exception;

/**
 * Thrown when an order is constructed with invalid parameters, or when an operation
 * would move an order into an invalid state (e.g. filling more than the remaining quantity,
 * or cancelling an order that is already terminal).
 */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
