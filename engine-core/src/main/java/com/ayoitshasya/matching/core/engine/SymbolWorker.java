package com.ayoitshasya.matching.core.engine;

import com.ayoitshasya.matching.core.book.OrderBook;
import com.ayoitshasya.matching.core.event.TradeListener;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Owns exactly one {@link OrderBook} and the single thread allowed to mutate it. Every
 * {@link Command} addressed to this symbol is enqueued here and executed strictly in arrival
 * order by that one thread — the design that lets {@code OrderBook} itself stay lock-free (see
 * its class-level docs): no second thread ever observes or mutates the book concurrently with
 * the writer.
 *
 * <p>{@link #offer} and {@link #shutdown} are both {@code synchronized} on this instance so that
 * "add a task to the queue" and "close the queue" can never interleave: once {@link #shutdown}
 * has run, every subsequent {@link #offer} sees {@link #closed} and rejects instead of queuing a
 * task that would sit behind the poison pill forever, un-run and its future never completed. See
 * NOTES-CONCURRENCY.md for the hang this prevents.
 */
final class SymbolWorker {

    private static final Task<?> POISON_PILL = new Task<Void>(null, null);

    private final OrderBook book;
    private final LinkedBlockingQueue<Task<?>> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private boolean closed = false;

    SymbolWorker(String symbol, List<? extends TradeListener> initialListeners) {
        this.book = new OrderBook(symbol);
        initialListeners.forEach(book::addListener);
        this.thread = new Thread(this::run, "matching-writer-" + symbol);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    OrderBook book() {
        return book;
    }

    /** @return false if this worker has already been asked to shut down */
    synchronized boolean offer(Task<?> task) {
        if (closed) {
            return false;
        }
        queue.add(task);
        return true;
    }

    synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        queue.add(POISON_PILL);
    }

    void awaitTermination() throws InterruptedException {
        thread.join();
    }

    private void run() {
        while (true) {
            Task<?> task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == POISON_PILL) {
                return;
            }
            task.runOn(book);
        }
    }

    /**
     * A command paired with the future its result (or failure) is delivered through. A command
     * that throws — an invalid order, an unknown order ID — completes its own future
     * exceptionally and nothing more: the exception must never escape {@link #runOn} and kill
     * the writer thread, which would silently stop this entire symbol from matching.
     */
    record Task<T>(Command<T> command, CompletableFuture<T> future) {

        void runOn(OrderBook book) {
            try {
                future.complete(execute(command, book));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }

    /**
     * Dispatches a command to the one {@code OrderBook} method that implements it. The single
     * unchecked cast is safe by construction: every {@link Command} subtype declares the exact
     * result type its branch below produces, so {@code T} always matches the branch actually
     * taken for it.
     */
    @SuppressWarnings("unchecked")
    private static <T> T execute(Command<T> command, OrderBook book) {
        return (T) switch (command) {
            case PlaceOrder(var order) -> book.submit(order);
            case PlaceStopOrder(var order) -> {
                book.submitStop(order);
                yield null;
            }
            case CancelOrder cancelOrder -> {
                book.cancel(cancelOrder.orderId());
                yield null;
            }
            case CancelStopOrder cancelStopOrder -> {
                book.cancelStop(cancelStopOrder.orderId());
                yield null;
            }
        };
    }
}
