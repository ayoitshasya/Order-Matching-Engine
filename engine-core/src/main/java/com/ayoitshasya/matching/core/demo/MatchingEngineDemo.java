package com.ayoitshasya.matching.core.demo;

import com.ayoitshasya.matching.core.book.PendingStopView;
import com.ayoitshasya.matching.core.book.RestingOrderView;
import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.MarketOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.engine.MatchingEngine;
import com.ayoitshasya.matching.core.event.ConsoleMarketDataPrinter;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A runnable, narrated walkthrough of {@link MatchingEngine}, with no test framework and no
 * assertions: places a realistic sequence of orders for one symbol — resting orders on both
 * sides, a crossing order that partially fills one of them, a market order that sweeps the rest,
 * and a stop order triggered by the resulting activity — printing every trade and status change
 * as it happens, then the book's final state. Run it directly to see the engine work without
 * writing any code of your own:
 *
 * <pre>
 * mvn -pl engine-core compile exec:java -Dexec.mainClass=com.ayoitshasya.matching.core.demo.MatchingEngineDemo
 * </pre>
 *
 * or run {@code main} from an IDE.
 */
public final class MatchingEngineDemo {

    private static final String SYMBOL = "AAPL";

    private MatchingEngineDemo() {
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        MatchingEngine engine = new MatchingEngine();
        engine.addListener(new ConsoleMarketDataPrinter());

        System.out.println("Note: TRADE/ORDER lines below come from the listener, which runs on its own "
                + "thread by design (see the README's Concurrency Design) and can print slightly out of "
                + "order relative to the numbered steps around it. That's expected, not a bug.");

        section("Resting two sell orders and a buy order that doesn't cross either");
        place(engine, new LimitOrder(1, SYMBOL, Side.SELL, 10, 10_050, 0)); // rests at $100.50
        place(engine, new LimitOrder(2, SYMBOL, Side.SELL, 5, 10_000, 1));  // rests at $100.00, the better price
        place(engine, new LimitOrder(3, SYMBOL, Side.BUY, 4, 9_950, 2));    // rests at $99.50, best ask is $100.00

        section("A buy order that crosses and partially fills the best ask");
        place(engine, new LimitOrder(4, SYMBOL, Side.BUY, 3, 10_000, 3));   // trades 3 @ $100.00 against order 2

        section("A market order that sweeps whatever liquidity is left at the best ask");
        place(engine, new MarketOrder(5, SYMBOL, Side.BUY, 2, 4));          // trades 2 @ $100.00, order 2 is gone

        section("A pending stop - already eligible the moment it's placed, since the last trade "
                + "($100.00) is already at or below its $100.40 trigger. It won't fire until the "
                + "book processes another order, though: triggers are only checked once per "
                + "submission, not the instant a stop becomes eligible");
        placeStop(engine, new StopOrder(6, SYMBOL, Side.SELL, 4, 10_040, 5));

        section("Any further order lets the engine notice the pending stop and trigger it - even "
                + "one that doesn't cross anything itself");
        place(engine, new LimitOrder(7, SYMBOL, Side.BUY, 1, 9_900, 6)); // rests; triggers order 6 as a side effect

        section("Final book state");
        List<RestingOrderView> resting = engine.restingOrders(SYMBOL).get();
        List<PendingStopView> pending = engine.pendingStops(SYMBOL).get();
        OptionalLong lastTradePrice = engine.lastTradePrice(SYMBOL).get();

        System.out.println("Resting orders:");
        resting.forEach(order -> System.out.println("  " + order));
        System.out.println("Pending stops:");
        pending.forEach(stop -> System.out.println("  " + stop));
        String lastTrade = lastTradePrice.isPresent() ? "$" + formatPrice(lastTradePrice.getAsLong()) : "none";
        System.out.println("Last trade price: " + lastTrade);

        engine.shutdown();
    }

    private static void place(MatchingEngine engine, LimitOrder order) throws ExecutionException, InterruptedException {
        List<Trade> trades = engine.placeOrder(order).get();
        System.out.println("-> order " + order.getId() + " directly generated " + trades.size() + " trade(s)");
    }

    private static void place(MatchingEngine engine, MarketOrder order) throws ExecutionException, InterruptedException {
        List<Trade> trades = engine.placeOrder(order).get();
        System.out.println("-> order " + order.getId() + " directly generated " + trades.size() + " trade(s)");
    }

    private static void placeStop(MatchingEngine engine, StopOrder stop) throws ExecutionException, InterruptedException {
        CompletableFuture<Void> future = engine.placeStopOrder(stop);
        future.get();
        System.out.println("-> stop order " + stop.getId() + " is now pending");
    }

    private static void section(String heading) {
        System.out.println();
        System.out.println("=== " + heading + " ===");
    }

    private static String formatPrice(long ticks) {
        return String.format("%d.%02d", ticks / 100, ticks % 100);
    }
}
