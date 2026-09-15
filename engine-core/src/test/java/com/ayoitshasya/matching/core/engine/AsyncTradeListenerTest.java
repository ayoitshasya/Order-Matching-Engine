package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.TradeListener;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTradeListenerTest {

    @Test
    void aThrowingDelegateDoesNotPropagateOrStopFurtherEvents() throws InterruptedException {
        CountDownLatch secondEventSeen = new CountDownLatch(1);
        AtomicBoolean sawSecondTrade = new AtomicBoolean(false);
        AsyncTradeListener listener = new AsyncTradeListener(new TradeListener() {
            @Override
            public void onTrade(Trade trade) {
                if (trade.tradeId() == 1) {
                    throw new RuntimeException("boom");
                }
                sawSecondTrade.set(true);
                secondEventSeen.countDown();
            }

            @Override
            public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
            }
        });

        listener.onTrade(new Trade(1, 10, 20, 100, 5, 0));
        listener.onTrade(new Trade(2, 11, 21, 100, 5, 1));

        assertThat(secondEventSeen.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sawSecondTrade.get()).isTrue();

        listener.close();
    }

    @Test
    void eventsDeliveredAfterCloseAreDroppedRatherThanThrown() {
        AsyncTradeListener listener = new AsyncTradeListener(new TradeListener() {
            @Override
            public void onTrade(Trade trade) {
            }

            @Override
            public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
            }
        });

        listener.close();

        listener.onTrade(new Trade(1, 10, 20, 100, 5, 0));
    }
}
