package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeTest {

    @Test
    void constructsWithAllFields() {
        Trade trade = new Trade(1, 10, 20, 15_000, 50, 0);

        assertThat(trade.tradeId()).isEqualTo(1);
        assertThat(trade.buyOrderId()).isEqualTo(10);
        assertThat(trade.sellOrderId()).isEqualTo(20);
        assertThat(trade.price()).isEqualTo(15_000);
        assertThat(trade.quantity()).isEqualTo(50);
        assertThat(trade.sequence()).isZero();
    }

    @Test
    void twoTradesWithSameValuesAreEqual() {
        Trade trade1 = new Trade(1, 10, 20, 15_000, 50, 0);
        Trade trade2 = new Trade(1, 10, 20, 15_000, 50, 0);

        assertThat(trade1).isEqualTo(trade2);
        assertThat(trade1).hasSameHashCodeAs(trade2);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveTradeId(long invalidTradeId) {
        assertThatThrownBy(() -> new Trade(invalidTradeId, 10, 20, 15_000, 50, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Trade id");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveBuyOrderId(long invalidBuyOrderId) {
        assertThatThrownBy(() -> new Trade(1, invalidBuyOrderId, 20, 15_000, 50, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Buy order id");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveSellOrderId(long invalidSellOrderId) {
        assertThatThrownBy(() -> new Trade(1, 10, invalidSellOrderId, 15_000, 50, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Sell order id");
    }

    @Test
    void rejectsSelfTrade() {
        assertThatThrownBy(() -> new Trade(1, 10, 10, 15_000, 50, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("cannot trade with itself");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositivePrice(long invalidPrice) {
        assertThatThrownBy(() -> new Trade(1, 10, 20, invalidPrice, 50, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("price");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveQuantity(long invalidQuantity) {
        assertThatThrownBy(() -> new Trade(1, 10, 20, 15_000, invalidQuantity, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void rejectsNegativeSequence() {
        assertThatThrownBy(() -> new Trade(1, 10, 20, 15_000, 50, -1))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("sequence");
    }
}
