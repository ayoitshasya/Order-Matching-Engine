package com.ayoitshasya.matching.core.eventlog;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.engine.CancelOrder;
import com.ayoitshasya.matching.core.engine.PlaceOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogWriterTest {

    @Test
    void appendedCommandsAreWrittenToTheFileByTheTimeCloseReturns(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");
        EventLogWriter writer = new EventLogWriter(logFile);

        writer.append(new PlaceOrder(new LimitOrder(1, "AAPL", Side.BUY, 10, 100, 0)));
        writer.append(new CancelOrder("AAPL", 1));
        writer.close();

        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertThat(lines).containsExactly("PLACE_LIMIT|AAPL|1|BUY|10|100|0", "CANCEL|AAPL|1");
    }

    @Test
    void appendReturnsBeforeTheLineIsNecessarilyOnDisk(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");
        EventLogWriter writer = new EventLogWriter(logFile);

        // append must not block on I/O: this call has to return promptly regardless of how long
        // the (here, trivially fast) disk write ends up taking.
        writer.append(new CancelOrder("AAPL", 1));

        writer.close();
        assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8)).containsExactly("CANCEL|AAPL|1");
    }

    @Test
    void closingTwiceDoesNotThrow(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");
        EventLogWriter writer = new EventLogWriter(logFile);

        writer.append(new CancelOrder("AAPL", 1));
        writer.close();
        writer.close();
    }

    @Test
    void appendingToAnExistingFileContinuesRatherThanOverwriting(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("events.log");

        EventLogWriter first = new EventLogWriter(logFile);
        first.append(new CancelOrder("AAPL", 1));
        first.close();

        EventLogWriter second = new EventLogWriter(logFile);
        second.append(new CancelOrder("AAPL", 2));
        second.close();

        assertThat(Files.readAllLines(logFile, StandardCharsets.UTF_8))
                .containsExactly("CANCEL|AAPL|1", "CANCEL|AAPL|2");
    }
}
