package com.ayoitshasya.matching.bench;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.engine.MatchingEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Aggregate {@code MatchingEngine} throughput across several independent symbols, one JMH thread
 * per symbol so no two threads ever contend for the same symbol's queue — the parallel-symbols
 * scaling story a single-threaded {@code OrderBook} has no way to tell on its own (see
 * {@link OrderBookThroughputBenchmark}). Each op waits for its order's future to complete before
 * counting as done, so this measures true round-trip throughput (accepted, queued, matched, and
 * reported back to the caller) rather than just how fast commands can be enqueued.
 *
 * <p>These two throughput numbers answer different questions and are not meant to be compared
 * directly: {@code OrderBookThroughputBenchmark} is the ceiling for matching logic on one thread;
 * this one is what the engine actually delivers end-to-end, parallelized across symbols, queue
 * and future overhead included.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingEngineThroughputBenchmark {

    private static final int SYMBOL_THREADS = 4;
    private static final long SELL_PRICE_BASE = 2_000_000_000L;

    @State(Scope.Benchmark)
    public static class EngineState {

        MatchingEngine engine;

        @Setup(Level.Trial)
        public void setUp() {
            engine = new MatchingEngine();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            engine.shutdown();
        }
    }

    /** One instance per JMH thread, so each thread owns a distinct symbol — no cross-thread queue contention. */
    @State(Scope.Thread)
    public static class ThreadState {

        String symbol;
        long nextId = 1;

        @Setup(Level.Trial)
        public void setUp() {
            symbol = "SYM-" + Thread.currentThread().getId();
        }
    }

    @Benchmark
    @Threads(SYMBOL_THREADS)
    public void placeNonCrossingLimitOrder(EngineState engineState, ThreadState threadState) throws Exception {
        long id = threadState.nextId++;
        Side side = (id % 2 == 0) ? Side.BUY : Side.SELL;
        long price = side == Side.BUY ? (1 + id % 1000) : (SELL_PRICE_BASE - id % 1000);
        engineState.engine.placeOrder(new LimitOrder(id, threadState.symbol, side, 10, price, id)).get();
    }
}
