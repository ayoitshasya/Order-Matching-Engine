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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Raw, single-threaded {@code OrderBook} throughput: how many orders per second one thread can
 * push through matching and resting, with no queueing, futures, or thread handoff in the way.
 * This is the ceiling {@code MatchingEngine}'s per-symbol throughput is measured against in
 * {@link MatchingEngineThroughputBenchmark} — see that class for why the two numbers mean
 * different things.
 *
 * <p>Orders alternate sides at prices confined to disjoint ranges (buys near 1, sells near
 * 2,000,000,000), so nothing ever crosses: this is the cost of accepting and resting an order,
 * not of matching one, and the workload never depletes — the book only grows — so it stays
 * representative for the whole benchmark run rather than degenerating after the first few ops.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OrderBookThroughputBenchmark {

    private static final String SYMBOL = "BENCH";
    private static final long SELL_PRICE_BASE = 2_000_000_000L;

    private OrderBook book;
    private long nextId;

    @Setup(Level.Trial)
    public void setUp() {
        book = new OrderBook(SYMBOL);
        nextId = 1;
    }

    @Benchmark
    public void restNonCrossingLimitOrder() {
        long id = nextId++;
        Side side = (id % 2 == 0) ? Side.BUY : Side.SELL;
        long price = side == Side.BUY ? (1 + id % 1000) : (SELL_PRICE_BASE - id % 1000);
        book.submit(new LimitOrder(id, SYMBOL, side, 10, price, id));
    }
}
