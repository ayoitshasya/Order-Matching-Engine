package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.InMemoryTradeRecorder;
import com.ayoitshasya.matching.core.exception.EngineShutdownException;
import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import com.ayoitshasya.matching.core.exception.OrderNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingEngineTest {

    private static final String AAPL = "AAPL";
    private static final String MSFT = "MSFT";

    private final MatchingEngine engine = new MatchingEngine();
    private long nextSequence = 0;

    @AfterEach
    void shutDownEngine() {
        engine.shutdown();
    }

    private long seq() {
        return nextSequence++;
    }

    private LimitOrder limit(long id, String symbol, Side side, long quantity, long price) {
        return new LimitOrder(id, symbol, side, quantity, price, seq());
    }

    private static <T> T await(CompletableFuture<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    void placingCrossingOrdersProducesATradeOnTheAggressorsFuture() throws Exception {
        await(engine.placeOrder(limit(1, AAPL, Side.SELL, 10, 100)));
        List<Trade> trades = await(engine.placeOrder(limit(2, AAPL, Side.BUY, 10, 100)));

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).quantity()).isEqualTo(10);
        assertThat(trades.get(0).price()).isEqualTo(100);
    }

    @Test
    void restingOrderCanBeCancelledThroughTheEngine() throws Exception {
        await(engine.placeOrder(limit(1, AAPL, Side.BUY, 10, 100)));

        await(engine.cancelOrder(AAPL, 1));

        List<Trade> trades = await(engine.placeOrder(limit(2, AAPL, Side.SELL, 10, 100)));
        assertThat(trades).isEmpty();
    }

    @Test
    void differentSymbolsAreIndependentBooks() throws Exception {
        await(engine.placeOrder(limit(1, AAPL, Side.SELL, 10, 100)));
        List<Trade> msftTrades = await(engine.placeOrder(limit(2, MSFT, Side.BUY, 10, 100)));

        assertThat(msftTrades).isEmpty();
    }

    @Test
    void aCommandThatThrowsCompletesItsFutureExceptionally() {
        CompletableFuture<Void> future = engine.cancelOrder(AAPL, 999);

        assertThatThrownBy(() -> await(future))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void aThrowingCommandDoesNotKillItsWriterThread() throws Exception {
        CompletableFuture<Void> failed = engine.cancelOrder(AAPL, 999);
        assertThatThrownBy(() -> await(failed)).isInstanceOf(ExecutionException.class);

        await(engine.placeOrder(limit(1, AAPL, Side.SELL, 10, 100)));
        List<Trade> trades = await(engine.placeOrder(limit(2, AAPL, Side.BUY, 10, 100)));

        assertThat(trades).hasSize(1);
    }

    @Test
    void aDuplicateOrderIdFailsCleanlyWithoutKillingTheWriterThread() throws Exception {
        await(engine.placeOrder(limit(1, AAPL, Side.SELL, 10, 100)));

        CompletableFuture<List<Trade>> duplicate = engine.placeOrder(limit(1, AAPL, Side.BUY, 5, 100));
        assertThatThrownBy(() -> await(duplicate))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(InvalidOrderException.class);

        List<Trade> trades = await(engine.placeOrder(limit(2, AAPL, Side.BUY, 10, 100)));
        assertThat(trades).hasSize(1);
    }

    @Test
    void shutdownRejectsNewCommandsWithEngineShutdownException() {
        engine.shutdown();

        CompletableFuture<List<Trade>> future = engine.placeOrder(limit(1, AAPL, Side.BUY, 10, 100));

        assertThatThrownBy(() -> await(future))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(EngineShutdownException.class);
    }

    @Test
    void shutdownIsIdempotent() {
        engine.shutdown();
        engine.shutdown();
    }

    @Test
    void shutdownDrainsAlreadyQueuedCommandsBeforeStopping() throws Exception {
        CompletableFuture<List<Trade>> sell = engine.placeOrder(limit(1, AAPL, Side.SELL, 10, 100));
        CompletableFuture<List<Trade>> buy = engine.placeOrder(limit(2, AAPL, Side.BUY, 10, 100));

        engine.shutdown();

        assertThat(await(sell)).isEmpty();
        assertThat(await(buy)).hasSize(1);
    }

    @Test
    void aPendingStopOrderCanBePlacedAndCancelledThroughTheEngine() throws Exception {
        StopOrder stop = new StopOrder(1, AAPL, Side.BUY, 10, 150, seq());

        await(engine.placeStopOrder(stop));
        await(engine.cancelStopOrder(AAPL, 1));

        CompletableFuture<Void> secondCancel = engine.cancelStopOrder(AAPL, 1);
        assertThatThrownBy(() -> await(secondCancel))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void aTriggeredStopOrderCascadesThroughTheEngineToListenersOnly() throws Exception {
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        engine.addListener(recorder);

        await(engine.placeOrder(limit(1, AAPL, Side.SELL, 20, 100)));
        await(engine.placeStopOrder(new StopOrder(2, AAPL, Side.BUY, 5, 100, seq())));
        List<Trade> directTrades = await(engine.placeOrder(limit(3, AAPL, Side.BUY, 10, 100)));

        assertThat(directTrades).hasSize(1);
        assertThat(directTrades.get(0).quantity()).isEqualTo(10);

        engine.shutdown();

        assertThat(recorder.getTrades()).hasSize(2);
        assertThat(recorder.getTrades().stream().mapToLong(Trade::quantity).sum()).isEqualTo(15);
    }

    @Test
    void addListenerAppliesToBooksCreatedBeforeItWasRegistered() throws Exception {
        await(engine.placeOrder(limit(1, AAPL, Side.SELL, 10, 100)));

        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        engine.addListener(recorder);

        await(engine.placeOrder(limit(2, AAPL, Side.BUY, 10, 100)));
        engine.shutdown();

        assertThat(recorder.getTrades()).hasSize(1);
    }
}
