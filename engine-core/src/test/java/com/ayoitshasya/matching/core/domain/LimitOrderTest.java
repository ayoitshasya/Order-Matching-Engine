package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitOrderTest {

    @Test
    void constructsWithNewStatusAndFullRemainingQuantity() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        assertThat(order.getId()).isEqualTo(1);
        assertThat(order.getSymbol()).isEqualTo("AAPL");
        assertThat(order.getSide()).isEqualTo(Side.BUY);
        assertThat(order.getQuantity()).isEqualTo(100);
        assertThat(order.getRemainingQuantity()).isEqualTo(100);
        assertThat(order.getFilledQuantity()).isZero();
        assertThat(order.getPrice()).isEqualTo(15_000);
        assertThat(order.getSequence()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(order.isActive()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositiveId(long invalidId) {
        assertThatThrownBy(() -> new LimitOrder(invalidId, "AAPL", Side.BUY, 100, 15_000, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankSymbol(String blankSymbol) {
        assertThatThrownBy(() -> new LimitOrder(1, blankSymbol, Side.BUY, 100, 15_000, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    void rejectsNullSymbol() {
        assertThatThrownBy(() -> new LimitOrder(1, null, Side.BUY, 100, 15_000, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    void rejectsNullSide() {
        assertThatThrownBy(() -> new LimitOrder(1, "AAPL", null, 100, 15_000, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("side");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositiveQuantity(long invalidQuantity) {
        assertThatThrownBy(() -> new LimitOrder(1, "AAPL", Side.BUY, invalidQuantity, 15_000, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("quantity");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositivePrice(long invalidPrice) {
        assertThatThrownBy(() -> new LimitOrder(1, "AAPL", Side.BUY, 100, invalidPrice, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("price");
    }

    @Test
    void rejectsNegativeSequence() {
        assertThatThrownBy(() -> new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, -1))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void partialFillMovesToPartiallyFilledAndReducesRemainingQuantity() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        order.fill(40);

        assertThat(order.getRemainingQuantity()).isEqualTo(60);
        assertThat(order.getFilledQuantity()).isEqualTo(40);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.isActive()).isTrue();
    }

    @Test
    void fullFillMovesToFilledAndZerosRemainingQuantity() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        order.fill(100);

        assertThat(order.getRemainingQuantity()).isZero();
        assertThat(order.getFilledQuantity()).isEqualTo(100);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.isActive()).isFalse();
    }

    @Test
    void successiveFillsAccumulateUntilFilled() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        order.fill(30);
        order.fill(30);
        order.fill(40);

        assertThat(order.getRemainingQuantity()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -50})
    void rejectsNonPositiveFillQuantity(long invalidFillQuantity) {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        assertThatThrownBy(() -> order.fill(invalidFillQuantity))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Fill quantity");

        assertThat(order.getRemainingQuantity()).isEqualTo(100);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void rejectsFillQuantityExceedingRemainingQuantity() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.fill(70);

        assertThatThrownBy(() -> order.fill(31))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("exceeds remaining quantity");

        assertThat(order.getRemainingQuantity()).isEqualTo(30);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void cannotFillAFilledOrder() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.fill(100);

        assertThatThrownBy(() -> order.fill(1))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void cannotFillACancelledOrder() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.cancel();

        assertThatThrownBy(() -> order.fill(1))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void cancelFromNewMovesToCancelled() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.isActive()).isFalse();
    }

    @Test
    void cancelFromPartiallyFilledMovesToCancelledAndKeepsFilledQuantity() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.fill(40);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFilledQuantity()).isEqualTo(40);
        assertThat(order.getRemainingQuantity()).isEqualTo(60);
    }

    @Test
    void cannotCancelAFilledOrder() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.fill(100);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void cannotCancelAnAlreadyCancelledOrder() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void rejectFromNewMovesToRejected() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        order.reject();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.isActive()).isFalse();
    }

    @Test
    void cannotRejectAPartiallyFilledOrder() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.fill(1);

        assertThatThrownBy(order::reject)
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("status");
    }

    @Test
    void cannotRejectAnAlreadyRejectedOrder() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        order.reject();

        assertThatThrownBy(order::reject)
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("status");
    }

    @Test
    void equalityIsBasedOnIdAlone() {
        LimitOrder order1 = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        LimitOrder order2 = new LimitOrder(1, "MSFT", Side.SELL, 5, 30_000, 7);
        LimitOrder order3 = new LimitOrder(2, "AAPL", Side.BUY, 100, 15_000, 0);

        assertThat(order1).isEqualTo(order2);
        assertThat(order1).hasSameHashCodeAs(order2);
        assertThat(order1).isNotEqualTo(order3);
        assertThat(order1).isNotEqualTo(null);
        assertThat(order1).isNotEqualTo("not an order");
    }

    @Test
    void toStringContainsKeyFields() {
        LimitOrder order = new LimitOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        assertThat(order.toString())
                .contains("id=1")
                .contains("AAPL")
                .contains("BUY")
                .contains("15000");
    }
}
