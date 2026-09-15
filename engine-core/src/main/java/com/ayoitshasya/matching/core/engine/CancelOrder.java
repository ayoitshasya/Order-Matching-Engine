package com.ayoitshasya.matching.core.engine;

/** Cancels a resting order by ID. Completes with no result once the cancellation is applied. */
public record CancelOrder(String symbol, long orderId) implements Command<Void> {
}
