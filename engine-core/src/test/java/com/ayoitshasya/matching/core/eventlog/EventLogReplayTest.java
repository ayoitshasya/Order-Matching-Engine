package com.ayoitshasya.matching.core.eventlog;

import com.ayoitshasya.matching.core.book.PendingStopView;
import com.ayoitshasya.matching.core.book.RestingOrderView;
import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.MarketOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.engine.MatchingEngine;
import com.ayoitshasya.matching.core.exception.EventLogCorruptionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventLogReplayTest {

    private static final String AAPL = "AAPL";
    private static final String MSFT = "MSFT";

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    /** Snapshot of one symbol's book, independent of which engine produced it. */
    private record BookSnapshot(List<RestingOrderView> resting, List<PendingStopView> stops, OptionalLong lastTrade) {
    }

    private static BookSnapshot snapshot(MatchingEngine engine, String symbol) throws Exception {
        return new BookSnapshot(
                await(engine.restingOrders(symbol)),
                await(engine.pendingStops(symbol)),
                await(engine.lastTradePrice(symbol)));
    }

    private static void runWorkload(MatchingEngine engine) throws Exception {
        long seq = 0;

        // AAPL: resting orders on both sides, a partial fill, a full fill removing a level, and
        // a cancel of a still-resting order.
        await(engine.placeOrder(new LimitOrder(1, AAPL, Side.SELL, 10, 105, seq++)));
        await(engine.placeOrder(new LimitOrder(2, AAPL, Side.SELL, 5, 100, seq++)));
        await(engine.placeOrder(new LimitOrder(3, AAPL, Side.BUY, 3, 100, seq++)));
        await(engine.placeOrder(new LimitOrder(4, AAPL, Side.BUY, 20, 100, seq++)));
        await(engine.cancelOrder(AAPL, 1));

        // AAPL: a pending stop that stays pending, and a resting sell that a later buy will
        // cross to push the last trade price down and trigger it.
        await(engine.placeStopOrder(new StopOrder(5, AAPL, Side.SELL, 2, 50, seq++)));
        await(engine.cancelOrder(AAPL, 4));
        await(engine.placeOrder(new LimitOrder(6, AAPL, Side.SELL, 6, 85, seq++)));
        await(engine.placeStopOrder(new StopOrder(7, AAPL, Side.SELL, 4, 90, seq++)));
        await(engine.placeOrder(new LimitOrder(8, AAPL, Side.BUY, 6, 85, seq++)));
        await(engine.placeOrder(new LimitOrder(14, AAPL, Side.SELL, 7, 200, seq++)));

        // MSFT: independent of AAPL, exercises a market order and a stop-limit that rests once
        // triggered.
        await(engine.placeOrder(new LimitOrder(9, MSFT, Side.BUY, 10, 200, seq++)));
        await(engine.placeStopOrder(new StopOrder(10, MSFT, Side.SELL, 5, 250, 245, seq++)));
        await(engine.placeStopOrder(new StopOrder(11, MSFT, Side.BUY, 3, 500, seq++)));
        await(engine.placeOrder(new LimitOrder(12, MSFT, Side.SELL, 4, 200, seq++)));
        await(engine.placeOrder(new MarketOrder(13, MSFT, Side.SELL, 2, seq++)));
    }

    @Test
    void replayingTheEventLogRebuildsAnIdenticalBook(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");

        MatchingEngine original = new MatchingEngine(new EventLogWriter(logFile));
        runWorkload(original);

        BookSnapshot originalAapl = snapshot(original, AAPL);
        BookSnapshot originalMsft = snapshot(original, MSFT);
        original.shutdown();

        // Sanity: the workload actually produced something worth comparing, not two empty books.
        assertThat(originalAapl.resting()).isNotEmpty();
        assertThat(originalAapl.stops()).isNotEmpty();
        assertThat(originalAapl.lastTrade()).isPresent();
        assertThat(originalMsft.resting()).isNotEmpty();
        assertThat(originalMsft.stops()).isNotEmpty();
        assertThat(originalMsft.lastTrade()).isPresent();

        MatchingEngine replayed = new MatchingEngine();
        try {
            EventLogReplayer.replay(logFile, replayed);

            assertThat(snapshot(replayed, AAPL)).isEqualTo(originalAapl);
            assertThat(snapshot(replayed, MSFT)).isEqualTo(originalMsft);
        } finally {
            replayed.shutdown();
        }
    }

    @Test
    void aTruncatedFinalLineIsDiscardedRatherThanFailingReplay(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");

        MatchingEngine original = new MatchingEngine(new EventLogWriter(logFile));
        await(original.placeOrder(new LimitOrder(1, AAPL, Side.SELL, 10, 100, 0)));
        await(original.placeOrder(new LimitOrder(2, AAPL, Side.SELL, 5, 105, 1)));
        BookSnapshot expected = snapshot(original, AAPL);
        original.shutdown();

        // Simulate a crash mid-write of a third, never-completed line.
        Files.writeString(logFile, "PLACE_LIMIT|AAPL|3|BUY|7", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        MatchingEngine replayed = new MatchingEngine();
        try {
            EventLogReplayer.replay(logFile, replayed);

            assertThat(snapshot(replayed, AAPL)).isEqualTo(expected);
        } finally {
            replayed.shutdown();
        }
    }

    @Test
    void corruptionInTheMiddleOfTheLogFailsReplayLoudly(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");

        MatchingEngine original = new MatchingEngine(new EventLogWriter(logFile));
        await(original.placeOrder(new LimitOrder(1, AAPL, Side.SELL, 10, 100, 0)));
        await(original.placeOrder(new LimitOrder(2, AAPL, Side.SELL, 5, 105, 1)));
        original.shutdown();

        List<String> lines = new ArrayList<>(Files.readAllLines(logFile, StandardCharsets.UTF_8));
        lines.set(0, "GARBAGE NOT A VALID LINE");
        Files.write(logFile, lines, StandardCharsets.UTF_8);

        MatchingEngine replayed = new MatchingEngine();
        try {
            assertThatThrownBy(() -> EventLogReplayer.replay(logFile, replayed))
                    .isInstanceOf(EventLogCorruptionException.class);
        } finally {
            replayed.shutdown();
        }
    }
}
