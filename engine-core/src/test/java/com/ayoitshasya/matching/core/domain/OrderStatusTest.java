package com.ayoitshasya.matching.core.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"FILLED", "CANCELLED", "REJECTED", "TRIGGERED"})
    void terminalStatusesReportAsTerminal(OrderStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"NEW", "PARTIALLY_FILLED"})
    void nonTerminalStatusesReportAsNotTerminal(OrderStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }
}
