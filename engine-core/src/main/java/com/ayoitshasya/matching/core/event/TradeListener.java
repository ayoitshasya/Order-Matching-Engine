package com.ayoitshasya.matching.core.event;

import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Trade;

/**
 * Observer notified of matching activity in an order book: every trade, and every order
 * status transition.
 *
 * <p>A listener that throws is isolated by the book — see the order book's Design Decisions
 * entry in the project README for why a broken listener must never be able to corrupt or
 * abort matching.
 */
public interface TradeListener {

    void onTrade(Trade trade);

    void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus);
}
