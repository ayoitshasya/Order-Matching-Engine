package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.book.OrderBook;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.domain.TradableOrder;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.TradeListener;
import com.ayoitshasya.matching.core.exception.EngineShutdownException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A multithreaded matching engine: one {@link OrderBook} per symbol, each owned by exactly one
 * writer thread ({@link SymbolWorker}) that drains a dedicated queue of {@link Command}s for
 * that symbol alone. A symbol's book is never touched by more than one thread, so the book
 * itself needs no internal locking — see its class-level docs.
 *
 * <p>Every public method here builds a {@link Command}, hands it to the owning symbol's worker,
 * and returns a {@link CompletableFuture} that the writer thread completes once it has actually
 * processed that command: normally on success, exceptionally if the command threw (an invalid
 * order, an unknown order ID). A command throwing never kills its writer thread — only that one
 * command's future is affected, and the thread loops back for its next command. See
 * NOTES-CONCURRENCY.md for the races found (and fixed) while building this.
 *
 * <p>Trade listeners registered here are never given directly to an {@code OrderBook}; each is
 * wrapped in an {@link AsyncTradeListener} so a slow listener can only ever delay itself, not the
 * writer thread that produced the event. See that class for why.
 *
 * <p>{@link #shutdown()} stops accepting new commands, lets every symbol's queue drain to
 * completion, joins every writer thread, then closes every listener's executor. A command
 * submitted after shutdown has started fails its future immediately with
 * {@link EngineShutdownException} rather than hanging.
 */
public final class MatchingEngine {

    private final Map<String, SymbolWorker> workers = new ConcurrentHashMap<>();
    private final List<AsyncTradeListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean shuttingDown = false;

    /** Submits an order for immediate matching against its symbol's book. */
    public CompletableFuture<List<Trade>> placeOrder(TradableOrder order) {
        return submit(new PlaceOrder(order));
    }

    /** Parks a stop order in its symbol's pending stop book. */
    public CompletableFuture<Void> placeStopOrder(StopOrder order) {
        return submit(new PlaceStopOrder(order));
    }

    /** Cancels a resting order. */
    public CompletableFuture<Void> cancelOrder(String symbol, long orderId) {
        return submit(new CancelOrder(symbol, orderId));
    }

    /** Cancels a pending stop order. */
    public CompletableFuture<Void> cancelStopOrder(String symbol, long orderId) {
        return submit(new CancelStopOrder(symbol, orderId));
    }

    /**
     * Registers a listener across every symbol this engine trades: existing symbols immediately,
     * and any symbol whose first command arrives later. The listener is wrapped in an
     * {@link AsyncTradeListener} so it runs on its own dedicated thread rather than the caller's
     * writer thread.
     */
    public void addListener(TradeListener listener) {
        AsyncTradeListener wrapped = new AsyncTradeListener(listener);
        listeners.add(wrapped);
        for (SymbolWorker worker : workers.values()) {
            worker.book().addListener(wrapped);
        }
    }

    /**
     * Stops accepting new commands, waits for every symbol's queue to fully drain, joins every
     * writer thread, then closes every listener's executor. Idempotent: a second call returns
     * immediately.
     */
    public void shutdown() {
        lifecycle.writeLock().lock();
        try {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
        } finally {
            lifecycle.writeLock().unlock();
        }

        for (SymbolWorker worker : workers.values()) {
            worker.shutdown();
        }
        for (SymbolWorker worker : workers.values()) {
            try {
                worker.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (AsyncTradeListener listener : listeners) {
            listener.close();
        }
    }

    /**
     * Enqueues {@code command} on its symbol's worker, creating that worker on first use.
     *
     * <p>Submission takes the lifecycle lock's read side and shutdown takes its write side:
     * many submissions can proceed concurrently, but none can run while a shutdown is in
     * progress, and a shutdown always finishes seeing every worker that will ever exist. Without
     * this, a worker could be created (and a command queued to it) after {@link #shutdown} had
     * already finished iterating {@link #workers} — a thread {@code shutdown} never joins and a
     * future that never completes. See NOTES-CONCURRENCY.md.
     */
    private <T> CompletableFuture<T> submit(Command<T> command) {
        lifecycle.readLock().lock();
        try {
            CompletableFuture<T> future = new CompletableFuture<>();
            if (shuttingDown) {
                future.completeExceptionally(
                        new EngineShutdownException("Engine is shutting down; rejected " + command));
                return future;
            }
            SymbolWorker worker = workers.computeIfAbsent(command.symbol(), this::newWorker);
            worker.offer(new SymbolWorker.Task<>(command, future));
            return future;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private SymbolWorker newWorker(String symbol) {
        return new SymbolWorker(symbol, listeners);
    }
}
