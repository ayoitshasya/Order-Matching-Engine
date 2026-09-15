package com.ayoitshasya.matching.core.event;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleMarketDataPrinterTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void redirectStdOut() {
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdOut() {
        System.setOut(originalOut);
    }

    @Test
    void printsTradeDetails() {
        new ConsoleMarketDataPrinter().onTrade(new Trade(1, 10, 20, 15_000, 5, 0));

        assertThat(capturedOut.toString(StandardCharsets.UTF_8))
                .contains("TRADE")
                .contains("buy=10")
                .contains("sell=20")
                .contains("price=15000")
                .contains("qty=5");
    }

    @Test
    void printsOrderStatusChange() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        new ConsoleMarketDataPrinter().onOrderStatusChanged(order, OrderStatus.NEW, OrderStatus.CANCELLED);

        assertThat(capturedOut.toString(StandardCharsets.UTF_8))
                .contains("ORDER")
                .contains("id=1")
                .contains("NEW -> CANCELLED");
    }
}
