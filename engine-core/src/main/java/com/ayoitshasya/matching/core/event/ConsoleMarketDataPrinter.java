package com.ayoitshasya.matching.core.event;

import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Trade;

/**
 * Prints every trade and order status change to standard output, as a minimal stand-in for a
 * real market data feed.
 */
public final class ConsoleMarketDataPrinter implements TradeListener {

    @Override
    public void onTrade(Trade trade) {
        System.out.printf(
                "TRADE id=%d buy=%d sell=%d price=%d qty=%d seq=%d%n",
                trade.tradeId(), trade.buyOrderId(), trade.sellOrderId(),
                trade.price(), trade.quantity(), trade.sequence());
    }

    @Override
    public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
        System.out.printf("ORDER id=%d %s -> %s%n", order.getId(), previousStatus, newStatus);
    }
}
