package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.book.OrderBook;
import com.ayoitshasya.matching.core.book.PendingStopView;
import com.ayoitshasya.matching.core.book.RestingOrderView;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.domain.TradableOrder;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.TradeListener;
import com.ayoitshasya.matching.core.exception.EngineShutdownException;
import com.ayoitshasya.matching.core.eventlog.EventLogWriter;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

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
 * completion, joins every writer thread, then closes every listener's executor (and the event
 * log, if one is configured). A command submitted after shutdown has started fails its future
 * immediately with {@link EngineShutdownException} rather than hanging.
 *
 * <p>If constructed with an {@link EventLogWriter}, every accepted command is appended to it from
 * inside the owning symbol's writer thread, in exactly the order that thread processes commands —
 * see {@link EventLogWriter} for why that placement matters and how it stays off the hot path.
 */
public final class MatchingEngine {

    private final Map<String, SymbolWorker> workers = new ConcurrentHashMap<>();
    private final List<AsyncTradeListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final EventLogWriter eventLog;
    private volatile boolean shuttingDown = false;

    /** An engine that does not log the commands it processes. */
    public MatchingEngine() {
        this(null);
    }

    /** An engine that appends every accepted command to {@code eventLog} as it is processed. */
    public MatchingEngine(EventLogWriter eventLog) {
        this.eventLog = eventLog;
    }

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
     * @return every resting order in {@code symbol}'s book, in book order — see
     *         {@link OrderBook#restingOrders()}
     */
    public CompletableFuture<List<RestingOrderView>> restingOrders(String symbol) {
        return query(symbol, OrderBook::restingOrders);
    }

    /** @return every pending stop in {@code symbol}'s book — see {@link OrderBook#pendingStops()} */
    public CompletableFuture<List<PendingStopView>> pendingStops(String symbol) {
        return query(symbol, OrderBook::pendingStops);
    }

    /** @return {@code symbol}'s most recent trade price, or empty if it has never traded */
    public CompletableFuture<OptionalLong> lastTradePrice(String symbol) {
        return query(symbol, OrderBook::lastTradePrice);
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
     * writer thread, then closes every listener's executor and the event log (if configured).
     * Idempotent: a second call returns immediately.
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
        if (eventLog != null) {
            eventLog.close();
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
        return enqueue(command.symbol(), future -> new SymbolWorker.Task<>(command, future));
    }

    /**
     * Runs a read-only {@code reader} against {@code symbol}'s book, on that symbol's own writer
     * thread, so it can never race a concurrent mutation. Unlike {@link #submit}, nothing here is
     * appended to the event log: a query does not change state, so there is nothing to replay.
     * Private: callers only ever see the typed public methods above ({@link #restingOrders}, and
     * so on), never a raw function over an {@code OrderBook} — {@code MatchingEngine} still never
     * exposes {@code OrderBook} itself to a caller.
     */
    private <T> CompletableFuture<T> query(String symbol, Function<OrderBook, T> reader) {
        return enqueue(symbol, future -> new SymbolWorker.QueryTask<>(reader, future));
    }

    private <T> CompletableFuture<T> enqueue(
            String symbol, Function<CompletableFuture<T>, SymbolWorker.WorkItem> workItemFactory) {
        lifecycle.readLock().lock();
        try {
            CompletableFuture<T> future = new CompletableFuture<>();
            if (shuttingDown) {
                future.completeExceptionally(
                        new EngineShutdownException("Engine is shutting down; rejected a request for " + symbol));
                return future;
            }
            SymbolWorker worker = workers.computeIfAbsent(symbol, this::newWorker);
            worker.offer(workItemFactory.apply(future));
            return future;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private SymbolWorker newWorker(String symbol) {
        return new SymbolWorker(symbol, listeners, eventLog);
    }
}
