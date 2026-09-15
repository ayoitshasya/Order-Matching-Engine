package com.ayoitshasya.matching.core.eventlog;

import com.ayoitshasya.matching.core.engine.Command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Appends every command a {@code MatchingEngine} accepts to a line-based log file, without
 * blocking the writer thread that accepted it.
 *
 * <p>{@code MatchingEngine} logs a command from inside its per-symbol writer thread, right as
 * that thread dequeues the command to process it — the same thread order guarantees that already
 * make {@code OrderBook} lock-free apply here too, so the log for one symbol is written in
 * exactly the order that symbol's commands were actually processed, with no extra synchronization
 * needed to get that right.
 *
 * <p>{@link #append} only has to build the line (cheap: string concatenation, no I/O) and hand it
 * to this class's own dedicated single-thread executor; the actual file write happens later, on
 * that thread, never on the caller's. This is the same decoupling {@code AsyncTradeListener} uses
 * for listeners, for the same reason: a writer thread that had to wait on disk I/O for every
 * command would have its throughput bounded by disk latency instead of by matching logic.
 *
 * <p><b>Durability tradeoff.</b> {@link #append} returns before the line reaches disk. Every line
 * is flushed individually (pushed out of this process's buffers) as soon as it is written, so a
 * crash of this process can lose at most whatever lines were still sitting in this writer's
 * executor queue at the moment of the crash — not anything already written. A crash can still
 * catch a single line's write in progress, truncating it; see {@code EventLogReplayer} for how
 * that is handled on replay. {@code flush()} does not call {@code fsync} (no
 * {@code FileChannel.force}), so an OS crash or power loss between the flush and the OS actually
 * persisting the page to physical disk is not covered — a deliberate simplification for a
 * project prioritizing design and correctness demonstration over production durability
 * guarantees, and one that costs nothing on the matching hot path either way, since neither flush
 * nor fsync happens there.
 */
public final class EventLogWriter implements AutoCloseable {

    private final Writer out;
    private final ExecutorService executor;

    public EventLogWriter(Path path) throws IOException {
        this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.executor = Executors.newSingleThreadExecutor(EventLogWriter::newDaemonThread);
    }

    /**
     * Enqueues {@code command} to be appended to the log. Returns immediately: the actual disk
     * write happens later, on this writer's own dedicated thread.
     */
    public void append(Command<?> command) {
        String line = EventLogCodec.encode(command);
        executor.execute(() -> writeLine(line));
    }

    private void writeLine(String line) {
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            System.err.println("EventLogWriter failed to append a line: " + e);
        }
    }

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "event-log-writer");
        thread.setDaemon(true);
        return thread;
    }

    /** Waits for every already-queued line to be written, then closes the underlying file. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
