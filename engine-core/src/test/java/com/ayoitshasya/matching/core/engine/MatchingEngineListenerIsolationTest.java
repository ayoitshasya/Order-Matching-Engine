package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.TradeListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the design choice documented on {@link AsyncTradeListener}: a slow listener delays only
 * itself, never the writer thread that produced the event it is slow to process.
 */
class MatchingEngineListenerIsolationTest {

    private static final String SYMBOL = "AAPL";
    private static final long LISTENER_DELAY_MILLIS = 200;

    @Test
    void aSlowListenerNeverDelaysTheWriterThread() throws Exception {
        MatchingEngine engine = new MatchingEngine();
        int tradeCount = 5;
        CountDownLatch listenerSeenAllTrades = new CountDownLatch(tradeCount);
        AtomicInteger listenerCalls = new AtomicInteger();

        engine.addListener(new TradeListener() {
            @Override
            public void onTrade(Trade trade) {
                listenerCalls.incrementAndGet();
                sleepUninterruptibly(LISTENER_DELAY_MILLIS);
                listenerSeenAllTrades.countDown();
            }

            @Override
            public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
            }
        });

        try {
            engine.placeOrder(new LimitOrder(1, SYMBOL, Side.SELL, tradeCount, 100, 0))
                    .get(5, TimeUnit.SECONDS);

            Instant before = Instant.now();
            for (int i = 0; i < tradeCount; i++) {
                engine.placeOrder(new LimitOrder(2 + i, SYMBOL, Side.BUY, 1, 100, i + 1))
                        .get(5, TimeUnit.SECONDS);
            }
            Duration matchingDuration = Duration.between(before, Instant.now());

            // A listener that blocked the writer thread would force this loop to take at least
            // tradeCount * LISTENER_DELAY_MILLIS, since each order's future could not complete
            // until the previous trade's listener callback returned. It should instead finish in
            // a small fraction of that, since the writer thread never waits on the listener.
            assertThat(matchingDuration)
                    .isLessThan(Duration.ofMillis(tradeCount * LISTENER_DELAY_MILLIS / 2));

            assertThat(listenerSeenAllTrades.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(listenerCalls.get()).isEqualTo(tradeCount);
        } finally {
            engine.shutdown();
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
