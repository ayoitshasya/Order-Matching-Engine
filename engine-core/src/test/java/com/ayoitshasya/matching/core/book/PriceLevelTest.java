package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Side;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceLevelTest {

    private static LimitOrder order(long id, long quantity, long sequence) {
        return new LimitOrder(id, "AAPL", Side.BUY, quantity, 15_000, sequence);
    }

    @Test
    void startsEmpty() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);

        assertThat(level.isEmpty()).isTrue();
        assertThat(level.getOrderCount()).isZero();
        assertThat(level.getTotalQuantity()).isZero();
        assertThat(level.getPrice()).isEqualTo(15_000);
    }

    @Test
    void addOrderIncreasesCountAndTotalQuantity() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);

        level.addOrder(order(1, 100, 0));
        level.addOrder(order(2, 50, 1));

        assertThat(level.getOrderCount()).isEqualTo(2);
        assertThat(level.getTotalQuantity()).isEqualTo(150);
        assertThat(level.isEmpty()).isFalse();
    }

    @Test
    void ordersAreReturnedInFifoInsertionOrder() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);
        LimitOrder first = order(1, 100, 0);
        LimitOrder second = order(2, 50, 1);
        LimitOrder third = order(3, 25, 2);

        level.addOrder(first);
        level.addOrder(second);
        level.addOrder(third);

        assertThat(level.orders()).containsExactly(first, second, third);
    }

    @Test
    void ordersViewIsUnmodifiable() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);
        level.addOrder(order(1, 100, 0));

        List<LimitOrder> view = List.copyOf(level.orders());
        assertThatThrownBy(() -> level.orders().add(order(2, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(view).hasSize(1);
    }

    @Test
    void removeOrderReturnsRemovedOrderAndReducesTotalQuantity() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);
        LimitOrder removable = order(1, 100, 0);
        level.addOrder(removable);
        level.addOrder(order(2, 50, 1));

        LimitOrder removed = level.removeOrder(1);

        assertThat(removed).isSameAs(removable);
        assertThat(level.getOrderCount()).isEqualTo(1);
        assertThat(level.getTotalQuantity()).isEqualTo(50);
    }

    @Test
    void removeOrderReturnsNullWhenOrderNotPresent() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);
        level.addOrder(order(1, 100, 0));

        LimitOrder removed = level.removeOrder(999);

        assertThat(removed).isNull();
        assertThat(level.getOrderCount()).isEqualTo(1);
        assertThat(level.getTotalQuantity()).isEqualTo(100);
    }

    @Test
    void recordFillReducesTotalQuantityWithoutRemovingOrders() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);
        level.addOrder(order(1, 100, 0));

        level.recordFill(40);

        assertThat(level.getTotalQuantity()).isEqualTo(60);
        assertThat(level.getOrderCount()).isEqualTo(1);
    }

    @Test
    void iteratorSupportsRemovalDuringTraversal() {
        PriceLevel<LimitOrder> level = new PriceLevel<>(15_000);
        LimitOrder first = order(1, 100, 0);
        LimitOrder second = order(2, 50, 1);
        level.addOrder(first);
        level.addOrder(second);

        Iterator<LimitOrder> iterator = level.ordersInFifoOrder();
        assertThat(iterator.next()).isSameAs(first);
        iterator.remove();

        assertThat(level.getOrderCount()).isEqualTo(1);
        assertThat(level.orders()).containsExactly(second);
    }
}
