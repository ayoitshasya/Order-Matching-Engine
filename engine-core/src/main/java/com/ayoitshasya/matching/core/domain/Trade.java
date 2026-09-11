package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;

/**
 * An immutable record of a single match between a buy order and a sell order.
 *
 * <p>{@code price} and {@code quantity} are expressed in ticks, matching {@link LimitOrder}. See
 * the project README for why prices and quantities are represented as {@code long} ticks rather
 * than {@code double} or {@link java.math.BigDecimal}.
 *
 * <p>{@code sequence} is the engine's monotonic sequence number for this trade, used to order
 * trades deterministically in the event log independent of wall-clock time.
 */
public record Trade(
        long tradeId,
        long buyOrderId,
        long sellOrderId,
        long price,
        long quantity,
        long sequence) {

    public Trade {
        if (tradeId <= 0) {
            throw new InvalidOrderException("Trade id must be positive, got " + tradeId);
        }
        if (buyOrderId <= 0) {
            throw new InvalidOrderException("Buy order id must be positive, got " + buyOrderId);
        }
        if (sellOrderId <= 0) {
            throw new InvalidOrderException("Sell order id must be positive, got " + sellOrderId);
        }
        if (buyOrderId == sellOrderId) {
            throw new InvalidOrderException("An order cannot trade with itself: " + buyOrderId);
        }
        if (price <= 0) {
            throw new InvalidOrderException("Trade price must be positive, got " + price);
        }
        if (quantity <= 0) {
            throw new InvalidOrderException("Trade quantity must be positive, got " + quantity);
        }
        if (sequence < 0) {
            throw new InvalidOrderException("Trade sequence must not be negative, got " + sequence);
        }
    }
}
