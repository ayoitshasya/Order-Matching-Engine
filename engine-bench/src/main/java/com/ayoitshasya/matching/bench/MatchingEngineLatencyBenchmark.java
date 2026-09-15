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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Round-trip latency percentiles of {@code MatchingEngine.placeOrder} — enqueue, per-symbol
 * writer thread processing, future completion — at growing book depth, on a single symbol with a
 * single caller thread. Same workload and same depths as
 * {@link OrderBookRestLatencyBenchmark}: the difference between the two is exactly the queue,
 * future, and thread-handoff overhead {@code MatchingEngine} adds on top of the identical
 * underlying {@code OrderBook} logic.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchingEngineLatencyBenchmark {

    private static final String SYMBOL = "BENCH";
    private static final long SELL_PRICE_BASE = 1_000_000_000L;

    @Param({"10", "100", "1000"})
    public int depth;

    private MatchingEngine engine;
    private long nextId;
    private long nextBuyPrice;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        engine = new MatchingEngine();
        nextId = 1;
        for (int i = 0; i < depth; i++) {
            engine.placeOrder(new LimitOrder(nextId, SYMBOL, Side.SELL, 10, SELL_PRICE_BASE + i, nextId)).get();
            nextId++;
        }
        nextBuyPrice = 1;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        engine.shutdown();
    }

    @Benchmark
    public void placeRestingOrder() throws Exception {
        engine.placeOrder(new LimitOrder(nextId, SYMBOL, Side.BUY, 10, nextBuyPrice, nextId)).get();
        nextId++;
        nextBuyPrice++;
    }
}
