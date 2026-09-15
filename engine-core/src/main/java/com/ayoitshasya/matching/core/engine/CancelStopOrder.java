package com.ayoitshasya.matching.core.engine;

/**
 * Cancels a pending stop order by ID. Completes with no result once the cancellation is applied.
 */
public record CancelStopOrder(String symbol, long orderId) implements Command<Void> {
}
