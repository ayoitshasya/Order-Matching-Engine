package com.ayoitshasya.matching.bench;

import com.ayoitshasya.matching.core.book.OrderBook;
import com.ayoitshasya.matching.core.domain.LimitOrder;
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
 * Latency percentiles (p50/p99/p99.9, reported by JMH's {@code SampleTime} mode) of resting one
 * more non-crossing limit order, as the number of distinct price levels already on the opposite
 * side of the book grows — the cost {@code TreeMap} navigation adds as depth increases.
 *
 * <p>The book is pre-populated once per trial with {@code depth} SELL price levels, all far above
 * where the benchmarked BUY orders are ever priced, so the benchmarked operation never crosses
 * and never needs to reset between invocations — unlike {@link OrderBookMatchLatencyBenchmark},
 * which consumes the liquidity it measures against.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OrderBookRestLatencyBenchmark {

    private static final String SYMBOL = "BENCH";
    private static final long SELL_PRICE_BASE = 1_000_000_000L;

    @Param({"10", "100", "1000"})
    public int depth;

    private OrderBook book;
    private long nextId;
    private long nextBuyPrice;

    @Setup(Level.Trial)
    public void setUp() {
        book = new OrderBook(SYMBOL);
        nextId = 1;
        for (int i = 0; i < depth; i++) {
            book.submit(new LimitOrder(nextId, SYMBOL, Side.SELL, 10, SELL_PRICE_BASE + i, nextId));
            nextId++;
        }
        nextBuyPrice = 1;
    }

    @Benchmark
    public void restNewLimitOrder() {
        book.submit(new LimitOrder(nextId, SYMBOL, Side.BUY, 10, nextBuyPrice, nextId));
        nextId++;
        nextBuyPrice++;
    }
}
