package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;

/**
 * An order that only trades at its limit price or better.
 *
 * <p>{@code price} is expressed in ticks (1 tick = 0.01 in the quoted currency) rather than as a
 * floating-point or {@link java.math.BigDecimal} value. See the project README for why: {@code
 * double} accumulates rounding error across repeated arithmetic, and {@code BigDecimal} is too
 * costly to allocate and compare on the matching hot path. Conversion to and from a
 * human-readable decimal value happens only at the API boundary.
 */
public final class LimitOrder extends TradableOrder {

    private final long price;

    public LimitOrder(long id, String symbol, Side side, long quantity, long price, long sequence) {
        super(id, symbol, side, quantity, sequence);
        if (price <= 0) {
            throw new InvalidOrderException("Limit price must be positive, got " + price);
        }
        this.price = price;
    }

    public long getPrice() {
        return price;
    }

    @Override
    public boolean crosses(long oppositeBestPrice) {
        return getSide() == Side.BUY ? price >= oppositeBestPrice : price <= oppositeBestPrice;
    }

    @Override
    public void applyUnfilledRemainder(UnfilledRemainderHandler handler) {
        handler.rest(this);
    }

    @Override
    public String toString() {
        return "LimitOrder{"
                + "id=" + getId()
                + ", symbol='" + getSymbol() + '\''
                + ", side=" + getSide()
                + ", price=" + price
                + ", quantity=" + getQuantity()
                + ", remainingQuantity=" + getRemainingQuantity()
                + ", sequence=" + getSequence()
                + ", status=" + getStatus()
                + '}';
    }
}
