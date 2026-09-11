package com.ayoitshasya.matching.core.exception;

/**
 * Thrown when an order lookup (e.g. by order ID for cancellation) fails because
 * no such order exists in the book.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long orderId) {
        super("No order found with id " + orderId);
    }
}
