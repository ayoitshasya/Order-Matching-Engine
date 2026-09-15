package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.TradeListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Decouples a {@link TradeListener} from the writer thread that would otherwise call it
 * directly.
 *
 * <p>{@code OrderBook} calls its listeners synchronously, inline with matching. A listener that
 * throws is isolated there already, but a listener that merely runs slowly — a blocking network
 * call in a market-data feed, say — would still stall the calling writer thread for as long as it
 * runs, backing up that entire symbol's command queue behind it.
 *
 * <p>{@link MatchingEngine} never registers a raw listener with an {@code OrderBook} directly; it
 * wraps every listener in one of these, backed by its own single-thread executor. The writer
 * thread's call to {@link #onTrade} or {@link #onOrderStatusChanged} only has to enqueue a task
 * and return, so a slow or misbehaving listener can only ever delay itself — never the writer
 * thread that fed it, and never any other listener. Because the executor is a single thread
 * draining a FIFO queue, one symbol's events still arrive at the listener in the order its writer
 * thread produced them; a listener registered across several symbols may see those symbols'
 * events interleaved, but nothing ever ordered that interleaving to begin with.
 */
final class AsyncTradeListener implements TradeListener {

    private final TradeListener delegate;
    private final ExecutorService executor;

    AsyncTradeListener(TradeListener delegate) {
        this.delegate = delegate;
        this.executor = Executors.newSingleThreadExecutor(AsyncTradeListener::newDaemonThread);
    }

    @Override
    public void onTrade(Trade trade) {
        dispatch(() -> delegate.onTrade(trade));
    }

    @Override
    public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
        dispatch(() -> delegate.onOrderStatusChanged(order, previousStatus, newStatus));
    }

    private void dispatch(Runnable action) {
        try {
            executor.execute(() -> runIsolated(action));
        } catch (RejectedExecutionException e) {
            // The engine has already closed this listener's executor as part of shutting down;
            // an event still in flight at that point is dropped rather than delivered, the same
            // tradeoff shutdown makes for commands still arriving after it has started.
        }
    }

    private static void runIsolated(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.err.println("Async trade listener threw: " + e);
        }
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "matching-listener");
        thread.setDaemon(true);
        return thread;
    }

    /** Stops accepting new events and waits briefly for already-queued ones to be delivered. */
    void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
