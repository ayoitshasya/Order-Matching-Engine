package com.ayoitshasya.matching.core.eventlog;

import com.ayoitshasya.matching.core.engine.CancelOrder;
import com.ayoitshasya.matching.core.engine.CancelStopOrder;
import com.ayoitshasya.matching.core.engine.Command;
import com.ayoitshasya.matching.core.engine.MatchingEngine;
import com.ayoitshasya.matching.core.engine.PlaceOrder;
import com.ayoitshasya.matching.core.engine.PlaceStopOrder;
import com.ayoitshasya.matching.core.exception.EventLogCorruptionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Rebuilds every symbol's book by replaying an event log written by {@link EventLogWriter}
 * against a {@link MatchingEngine} — the intended use is at startup, before the engine accepts
 * any new commands, so the replayed history and any new traffic are never interleaved.
 *
 * <p>Each decoded command is resubmitted through {@code MatchingEngine}'s normal public API
 * ({@code placeOrder}, {@code placeStopOrder}, {@code cancelOrder}, {@code cancelStopOrder}) —
 * replay is not a separate code path from live matching, it is the same matching logic driven by
 * a file instead of a live caller. Per-symbol order is exactly what was originally logged (see
 * {@link EventLogWriter}), and every symbol's own queue is still processed by its own single
 * writer thread, so replaying is exactly as deterministic as the original run was.
 *
 * <p><b>Truncated or corrupt final line.</b> A crash mid-write is the realistic failure mode for
 * this log, and it can only ever corrupt or truncate the line that was being written at the
 * moment of the crash — every earlier line was already flushed in full (see
 * {@link EventLogWriter}). So: if the very last line in the file fails to decode, that is treated
 * as this expected case — it is discarded with a warning, and everything before it is replayed
 * normally. If any line <em>other than the last</em> fails to decode, that is not the expected
 * failure mode (a clean crash cannot corrupt a line and then keep writing valid ones after it) —
 * replay fails loudly with {@link EventLogCorruptionException} rather than silently skipping or
 * guessing at a log that may be corrupt for an unknown reason.
 */
public final class EventLogReplayer {

    private EventLogReplayer() {
    }

    /**
     * Reads every line of {@code logFile}, decodes it, and resubmits it to {@code engine}, then
     * waits for every resulting command to finish processing before returning.
     *
     * @throws IOException if the log file cannot be read
     * @throws EventLogCorruptionException if a line other than the last one fails to decode
     */
    public static void replay(Path logFile, MatchingEngine engine) throws IOException {
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }

            Command<?> command;
            try {
                command = EventLogCodec.decode(line);
            } catch (EventLogCorruptionException e) {
                if (i == lines.size() - 1) {
                    System.err.println(
                            "Discarding truncated or corrupt final event log line, consistent with a crash "
                                    + "mid-write: " + e.getMessage());
                    break;
                }
                throw e;
            }
            futures.add(dispatch(command, engine));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static CompletableFuture<?> dispatch(Command<?> command, MatchingEngine engine) {
        return switch (command) {
            case PlaceOrder(var order) -> engine.placeOrder(order);
            case PlaceStopOrder(var order) -> engine.placeStopOrder(order);
            case CancelOrder cancelOrder -> engine.cancelOrder(cancelOrder.symbol(), cancelOrder.orderId());
            case CancelStopOrder cancelStopOrder ->
                    engine.cancelStopOrder(cancelStopOrder.symbol(), cancelStopOrder.orderId());
        };
    }
}
