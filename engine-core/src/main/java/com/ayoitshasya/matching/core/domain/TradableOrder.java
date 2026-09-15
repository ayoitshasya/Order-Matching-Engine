package com.ayoitshasya.matching.core.domain;

/**
 * An order that can be matched directly against the book. Submitted to the order book, it
 * decides for itself — through these two methods — whether it can trade against a given
 * opposite price and what happens to whatever it doesn't fill. The book calls these methods
 * polymorphically and never inspects the concrete order type.
 *
 * <p>{@link StopOrder} deliberately does not extend this class: it is never matched directly.
 * It waits in a separate pending-stop structure and, once triggered, spawns a
 * {@code TradableOrder} (a {@link MarketOrder}, or a {@link LimitOrder} for a stop-limit) that
 * is submitted for matching in its place.
 */
public abstract class TradableOrder extends Order {

    protected TradableOrder(long id, String symbol, Side side, long quantity, long sequence) {
        super(id, symbol, side, quantity, sequence);
    }

    /**
     * @return true if this order can trade against a resting order quoted at
     *         {@code oppositeBestPrice}
     */
    public abstract boolean crosses(long oppositeBestPrice);

    /**
     * Disposes of whatever quantity is left unfilled once matching stops: a limit order rests,
     * a market order is cancelled. See {@link UnfilledRemainderHandler} for why this is a
     * double dispatch rather than a type check in the book.
     */
    public abstract void applyUnfilledRemainder(UnfilledRemainderHandler handler);
}
