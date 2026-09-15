package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.InMemoryTradeRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Stresses {@link MatchingEngine} with many producer threads submitting orders across several
 * symbols concurrently, and checks that matching is neither lossy nor duplicative.
 *
 * <p>Every symbol here is set up so its total BUY quantity exactly equals its total SELL
 * quantity, all at one crossing price. Regardless of the interleaving in which a single writer
 * thread happens to process a symbol's orders, that invariant guarantees the book ends up
 * completely flat: every order fully filled, nothing left resting. That gives a strong,
 * deterministic assertion — {@code getRemainingQuantity() == 0} for every order — despite the
 * genuinely nondeterministic arrival order the producer threads create.
 */
class MatchingEngineConcurrencyTest {

    private static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "GOOG", "AMZN");
    private static final int ORDERS_PER_SIDE_PER_SYMBOL = 200;
    private static final long QUANTITY_PER_ORDER = 10;
    private static final long PRICE = 100;
    private static final int PRODUCER_THREADS = 16;

    @Test
    void concurrentOrdersAcrossManySymbolsProduceNoLostOrDuplicatedVolume() throws Exception {
        MatchingEngine engine = new MatchingEngine();
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        engine.addListener(recorder);

        AtomicLong nextOrderId = new AtomicLong(1);
        AtomicLong nextSequence = new AtomicLong(0);
        List<LimitOrder> allOrders = new ArrayList<>();
        long expectedTotalQuantity = 0;
        for (String symbol : SYMBOLS) {
            for (int i = 0; i < ORDERS_PER_SIDE_PER_SYMBOL; i++) {
                allOrders.add(new LimitOrder(nextOrderId.getAndIncrement(), symbol, Side.BUY,
                        QUANTITY_PER_ORDER, PRICE, nextSequence.getAndIncrement()));
                allOrders.add(new LimitOrder(nextOrderId.getAndIncrement(), symbol, Side.SELL,
                        QUANTITY_PER_ORDER, PRICE, nextSequence.getAndIncrement()));
                expectedTotalQuantity += QUANTITY_PER_ORDER;
            }
        }
        Collections.shuffle(allOrders, new Random(42));

        ExecutorService executor = Executors.newFixedThreadPool(PRODUCER_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch submittedLatch = new CountDownLatch(allOrders.size());
        Queue<CompletableFuture<List<Trade>>> futures = new ConcurrentLinkedQueue<>();

        try {
            for (LimitOrder order : allOrders) {
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        futures.add(engine.placeOrder(order));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        submittedLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(submittedLatch.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        for (CompletableFuture<List<Trade>> future : futures) {
            assertThatCode(() -> future.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
        }

        for (LimitOrder order : allOrders) {
            assertThat(order.getRemainingQuantity())
                    .as("order %d (%s) should have fully matched", order.getId(), order.getSymbol())
                    .isZero();
        }

        engine.shutdown();

        long recordedQuantity = recorder.getTrades().stream().mapToLong(Trade::quantity).sum();
        assertThat(recordedQuantity).isEqualTo(expectedTotalQuantity);
    }
}
