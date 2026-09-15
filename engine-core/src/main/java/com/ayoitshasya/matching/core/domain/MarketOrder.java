package com.ayoitshasya.matching.core.domain;

/**
 * An order that trades immediately at whatever price is available, without a limit. It
 * crosses at any opposite price, so it always matches while the opposite side has any
 * quantity at all. Anything left unfilled — because the opposite side ran out, or was empty
 * to begin with — is cancelled rather than resting: a market order is never quoted in the
 * book.
 */
public final class MarketOrder extends TradableOrder {

    public MarketOrder(long id, String symbol, Side side, long quantity, long sequence) {
        super(id, symbol, side, quantity, sequence);
    }

    @Override
    public boolean crosses(long oppositeBestPrice) {
        return true;
    }

    @Override
    public void applyUnfilledRemainder(UnfilledRemainderHandler handler) {
        handler.cancelRemainder(this);
    }

    @Override
    public String toString() {
        return "MarketOrder{"
                + "id=" + getId()
                + ", symbol='" + getSymbol() + '\''
                + ", side=" + getSide()
                + ", quantity=" + getQuantity()
                + ", remainingQuantity=" + getRemainingQuantity()
                + ", sequence=" + getSequence()
                + ", status=" + getStatus()
                + '}';
    }
}
