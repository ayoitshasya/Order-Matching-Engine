package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.domain.TradableOrder;

import java.util.List;

/**
 * Submits {@code order} for immediate matching. Completes with the trades it directly generated,
 * exactly as {@code OrderBook.submit} returns them — cascaded stop trades are not included, only
 * delivered to trade listeners.
 */
public record PlaceOrder(TradableOrder order) implements Command<List<Trade>> {

    @Override
    public String symbol() {
        return order.getSymbol();
    }
}
