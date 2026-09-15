package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;

import java.util.OptionalLong;

/**
 * A resting instruction that is not part of the visible book at all: it waits in a separate
 * pending-stop structure until the last trade price crosses its stop price. A buy stop
 * triggers when the last trade price rises to or above its stop price; a sell stop triggers
 * when it falls to or below.
 *
 * <p>Once triggered, it {@link #trigger(long)}s into the {@link TradableOrder} that is
 * actually submitted for matching: a {@link MarketOrder} for a plain stop, or a
 * {@link LimitOrder} at {@code limitPrice} for a stop-limit. {@code StopOrder} deliberately
 * does not extend {@link TradableOrder} — it has no crossing rule or unfilled-remainder
 * behaviour of its own, because it is never matched directly.
 */
public final class StopOrder extends Order {

    private final long stopPrice;
    private final OptionalLong limitPrice;

    /** A stop order that becomes a market order once triggered. */
    public StopOrder(long id, String symbol, Side side, long quantity, long stopPrice, long sequence) {
        this(id, symbol, side, quantity, stopPrice, OptionalLong.empty(), sequence);
    }

    /** A stop-limit order that becomes a limit order at {@code limitPrice} once triggered. */
    public StopOrder(long id, String symbol, Side side, long quantity, long stopPrice, long limitPrice, long sequence) {
        this(id, symbol, side, quantity, stopPrice, OptionalLong.of(limitPrice), sequence);
    }

    private StopOrder(long id, String symbol, Side side, long quantity, long stopPrice,
                       OptionalLong limitPrice, long sequence) {
        super(id, symbol, side, quantity, sequence);
        if (stopPrice <= 0) {
            throw new InvalidOrderException("Stop price must be positive, got " + stopPrice);
        }
        if (limitPrice.isPresent() && limitPrice.getAsLong() <= 0) {
            throw new InvalidOrderException("Limit price must be positive, got " + limitPrice.getAsLong());
        }
        this.stopPrice = stopPrice;
        this.limitPrice = limitPrice;
    }

    public long getStopPrice() {
        return stopPrice;
    }

    public OptionalLong getLimitPrice() {
        return limitPrice;
    }

    public boolean isStopLimit() {
        return limitPrice.isPresent();
    }

    /** @return true if a trade at {@code lastTradePrice} would trigger this stop */
    public boolean isTriggeredBy(long lastTradePrice) {
        return getSide() == Side.BUY ? lastTradePrice >= stopPrice : lastTradePrice <= stopPrice;
    }

    /**
     * Moves this stop to {@link OrderStatus#TRIGGERED} and returns the order that should be
     * submitted for matching in its place: same ID, symbol, side, and remaining quantity, but
     * a market order (or a limit order at {@code limitPrice} for a stop-limit).
     *
     * @param spawnSequence the sequence number the spawned order takes on as it enters the
     *                       visible book for the first time — distinct from this stop's own
     *                       sequence, which records when the stop was placed, not when it
     *                       became active
     * @throws InvalidOrderException if this stop has already reached a terminal status
     */
    public TradableOrder trigger(long spawnSequence) {
        TradableOrder spawned = limitPrice.isPresent()
                ? new LimitOrder(getId(), getSymbol(), getSide(), getRemainingQuantity(), limitPrice.getAsLong(), spawnSequence)
                : new MarketOrder(getId(), getSymbol(), getSide(), getRemainingQuantity(), spawnSequence);
        moveToTerminalStatus(OrderStatus.TRIGGERED);
        return spawned;
    }

    @Override
    public String toString() {
        return "StopOrder{"
                + "id=" + getId()
                + ", symbol='" + getSymbol() + '\''
                + ", side=" + getSide()
                + ", stopPrice=" + stopPrice
                + ", limitPrice=" + (limitPrice.isPresent() ? Long.toString(limitPrice.getAsLong()) : "none")
                + ", quantity=" + getQuantity()
                + ", remainingQuantity=" + getRemainingQuantity()
                + ", sequence=" + getSequence()
                + ", status=" + getStatus()
                + '}';
    }
}
