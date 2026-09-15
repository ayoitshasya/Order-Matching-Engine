package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.StopOrder;

/** Parks {@code order} in the pending stop book. Completes with no result once accepted. */
public record PlaceStopOrder(StopOrder order) implements Command<Void> {

    @Override
    public String symbol() {
        return order.getSymbol();
    }
}
