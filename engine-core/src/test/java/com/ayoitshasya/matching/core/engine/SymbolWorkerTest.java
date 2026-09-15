package com.ayoitshasya.matching.core.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SymbolWorker} directly, at the package level, for the two idempotency
 * invariants {@link MatchingEngine} relies on but can never itself trigger through its public
 * API (its read/write lock design rules both races out — see {@code MatchingEngine.submit}'s
 * docs and NOTES-CONCURRENCY.md).
 */
class SymbolWorkerTest {

    @Test
    void offerAfterShutdownIsRejectedRatherThanQueuedBehindThePoisonPill() throws InterruptedException {
        SymbolWorker worker = new SymbolWorker("AAPL", List.of(), null);
        worker.shutdown();
        worker.awaitTermination();

        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean accepted = worker.offer(new SymbolWorker.Task<>(new CancelOrder("AAPL", 1), future));

        assertThat(accepted).isFalse();
        assertThat(future.isDone()).isFalse();
    }

    @Test
    void shutdownIsIdempotent() throws InterruptedException {
        SymbolWorker worker = new SymbolWorker("AAPL", List.of(), null);

        worker.shutdown();
        worker.shutdown();

        worker.awaitTermination();
    }
}
