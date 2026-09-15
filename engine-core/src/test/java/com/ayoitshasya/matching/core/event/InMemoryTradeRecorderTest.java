package com.ayoitshasya.matching.core.event;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTradeRecorderTest {

    @Test
    void recordsTradesInReceivedOrder() {
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        Trade first = new Trade(1, 10, 20, 15_000, 5, 0);
        Trade second = new Trade(2, 10, 21, 15_100, 3, 1);

        recorder.onTrade(first);
        recorder.onTrade(second);

        assertThat(recorder.getTrades()).containsExactly(first, second);
    }

    @Test
    void recordsStatusChangesInReceivedOrder() {
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        recorder.onOrderStatusChanged(order, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED);
        recorder.onOrderStatusChanged(order, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED);

        assertThat(recorder.getStatusChanges()).containsExactly(
                new InMemoryTradeRecorder.StatusChange(1, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED),
                new InMemoryTradeRecorder.StatusChange(1, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED));
    }

    @Test
    void exposedListsAreUnmodifiable() {
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();

        assertThatThrownBy(() -> recorder.getTrades().add(new Trade(1, 10, 20, 15_000, 5, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> recorder.getStatusChanges()
                .add(new InMemoryTradeRecorder.StatusChange(1, OrderStatus.NEW, OrderStatus.CANCELLED)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
