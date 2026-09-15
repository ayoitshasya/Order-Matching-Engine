package com.ayoitshasya.matching.core.event;

import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records every notification it receives, in the order it received them. Intended for
 * assertions in tests, not production use.
 */
public final class InMemoryTradeRecorder implements TradeListener {

    private final List<Trade> trades = new ArrayList<>();
    private final List<StatusChange> statusChanges = new ArrayList<>();

    @Override
    public void onTrade(Trade trade) {
        trades.add(trade);
    }

    @Override
    public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
        statusChanges.add(new StatusChange(order.getId(), previousStatus, newStatus));
    }

    public List<Trade> getTrades() {
        return Collections.unmodifiableList(trades);
    }

    public List<StatusChange> getStatusChanges() {
        return Collections.unmodifiableList(statusChanges);
    }

    public record StatusChange(long orderId, OrderStatus previousStatus, OrderStatus newStatus) {
    }
}
