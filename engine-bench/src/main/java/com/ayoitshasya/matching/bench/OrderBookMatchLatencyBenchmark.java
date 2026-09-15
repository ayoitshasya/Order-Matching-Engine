package com.ayoitshasya.matching.bench;

import com.ayoitshasya.matching.core.book.OrderBook;
import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.MarketOrder;
import com.ayoitshasya.matching.core.domain.Side;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Latency percentiles of a marketable order that must walk and fill {@code depth} resting orders
 * queued at a single price level in one sweep — the cost of {@code PriceLevel}'s FIFO iteration
 * and per-fill trade bookkeeping as queue depth grows.
 *
 * <p>Unlike {@link OrderBookRestLatencyBenchmark}, this operation consumes exactly the liquidity
 * it measures, so a fresh book is rebuilt before every single invocation ({@code Level.Invocation}
 * setup) rather than shared across the trial. This is the standard JMH pattern for a destructive
 * benchmark, and the tradeoff JMH documents for it is accepted here deliberately: the setup's own
 * allocation cost adds a small amount of noise inside the measured window, which is preferable to
 * the alternative of the benchmark silently degenerating into "match against an empty book" after
 * the first {@code depth} invocations exhaust a shared one.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OrderBookMatchLatencyBenchmark {

    private static final String SYMBOL = "BENCH";
    private static final long PRICE = 100;

    @Param({"10", "100", "1000"})
    public int depth;

    private OrderBook book;
    private long nextId;

    @Setup(Level.Invocation)
    public void setUp() {
        book = new OrderBook(SYMBOL);
        nextId = 1;
        for (int i = 0; i < depth; i++) {
            book.submit(new LimitOrder(nextId, SYMBOL, Side.SELL, 1, PRICE, nextId));
            nextId++;
        }
    }

    @Benchmark
    public void sweepAllRestingOrders() {
        book.submit(new MarketOrder(nextId, SYMBOL, Side.BUY, depth, nextId));
    }
}
