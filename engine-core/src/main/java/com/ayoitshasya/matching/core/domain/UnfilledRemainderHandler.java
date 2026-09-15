package com.ayoitshasya.matching.core.domain;

/**
 * Callback a {@link TradableOrder} uses to dispose of whatever quantity is left unfilled once
 * matching stops.
 *
 * <p>Splitting this into two type-specific methods, rather than one method taking the base
 * {@code Order} type, is what makes resting type-safe: only a {@link LimitOrder} can be passed
 * to {@link #rest(LimitOrder)}, so only a {@code LimitOrder} can ever enter the book's price
 * levels — enforced by the compiler, with no {@code instanceof} check anywhere.
 */
public interface UnfilledRemainderHandler {

    /** Rests a limit order's unfilled remainder in the book. */
    void rest(LimitOrder order);

    /** Cancels a non-resting order's unfilled remainder, e.g. an unfilled market order. */
    void cancelRemainder(TradableOrder order);
}
