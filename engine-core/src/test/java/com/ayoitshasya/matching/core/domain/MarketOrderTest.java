package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketOrderTest {

    @Test
    void constructsWithNewStatusAndFullRemainingQuantity() {
        MarketOrder order = new MarketOrder(1, "AAPL", Side.BUY, 100, 0);

        assertThat(order.getId()).isEqualTo(1);
        assertThat(order.getSymbol()).isEqualTo("AAPL");
        assertThat(order.getSide()).isEqualTo(Side.BUY);
        assertThat(order.getQuantity()).isEqualTo(100);
        assertThat(order.getRemainingQuantity()).isEqualTo(100);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositiveQuantity(long invalidQuantity) {
        assertThatThrownBy(() -> new MarketOrder(1, "AAPL", Side.BUY, invalidQuantity, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void crossesAtAnyPrice() {
        MarketOrder order = new MarketOrder(1, "AAPL", Side.BUY, 100, 0);

        assertThat(order.crosses(1)).isTrue();
        assertThat(order.crosses(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void applyUnfilledRemainderDelegatesToCancelRemainder() {
        MarketOrder order = new MarketOrder(1, "AAPL", Side.BUY, 100, 0);
        RecordingHandler handler = new RecordingHandler();

        order.applyUnfilledRemainder(handler);

        assertThat(handler.cancelledOrders).containsExactly(order);
        assertThat(handler.restedOrders).isEmpty();
    }

    @Test
    void fillAndCancelLifecycleMatchesBaseOrderBehaviour() {
        MarketOrder order = new MarketOrder(1, "AAPL", Side.BUY, 100, 0);

        order.fill(40);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.getRemainingQuantity()).isEqualTo(60);

        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.isActive()).isFalse();
    }

    @Test
    void toStringContainsKeyFields() {
        MarketOrder order = new MarketOrder(1, "AAPL", Side.BUY, 100, 0);

        assertThat(order.toString())
                .contains("id=1")
                .contains("AAPL")
                .contains("BUY");
    }

    private static final class RecordingHandler implements UnfilledRemainderHandler {
        private final List<LimitOrder> restedOrders = new ArrayList<>();
        private final List<TradableOrder> cancelledOrders = new ArrayList<>();

        @Override
        public void rest(LimitOrder order) {
            restedOrders.add(order);
        }

        @Override
        public void cancelRemainder(TradableOrder order) {
            cancelledOrders.add(order);
        }
    }
}
