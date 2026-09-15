package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StopOrderTest {

    @Test
    void plainStopHasNoLimitPrice() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        assertThat(stop.getStopPrice()).isEqualTo(15_000);
        assertThat(stop.getLimitPrice()).isEmpty();
        assertThat(stop.isStopLimit()).isFalse();
        assertThat(stop.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void stopLimitCarriesALimitPrice() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 15_100, 0);

        assertThat(stop.getStopPrice()).isEqualTo(15_000);
        assertThat(stop.getLimitPrice()).hasValue(15_100);
        assertThat(stop.isStopLimit()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveStopPrice(long invalidStopPrice) {
        assertThatThrownBy(() -> new StopOrder(1, "AAPL", Side.BUY, 100, invalidStopPrice, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Stop price");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveLimitPrice(long invalidLimitPrice) {
        assertThatThrownBy(() -> new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, invalidLimitPrice, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Limit price");
    }

    @Test
    void buyStopTriggersWhenLastTradePriceRisesToOrAboveStopPrice() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        assertThat(stop.isTriggeredBy(14_999)).isFalse();
        assertThat(stop.isTriggeredBy(15_000)).isTrue();
        assertThat(stop.isTriggeredBy(15_001)).isTrue();
    }

    @Test
    void sellStopTriggersWhenLastTradePriceFallsToOrBelowStopPrice() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.SELL, 100, 15_000, 0);

        assertThat(stop.isTriggeredBy(15_001)).isFalse();
        assertThat(stop.isTriggeredBy(15_000)).isTrue();
        assertThat(stop.isTriggeredBy(14_999)).isTrue();
    }

    @Test
    void triggeringAPlainStopProducesAMarketOrderAndMovesToTriggered() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 5);

        TradableOrder spawned = stop.trigger(7);

        assertThat(stop.getStatus()).isEqualTo(OrderStatus.TRIGGERED);
        assertThat(stop.isActive()).isFalse();
        assertThat(spawned).isInstanceOf(MarketOrder.class);
        assertThat(spawned.getId()).isEqualTo(1);
        assertThat(spawned.getSymbol()).isEqualTo("AAPL");
        assertThat(spawned.getSide()).isEqualTo(Side.BUY);
        assertThat(spawned.getQuantity()).isEqualTo(100);
        assertThat(spawned.getSequence()).isEqualTo(7);
    }

    @Test
    void triggeringAStopLimitProducesALimitOrderAtItsLimitPrice() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.SELL, 100, 15_000, 14_800, 5);

        TradableOrder spawned = stop.trigger(9);

        assertThat(stop.getStatus()).isEqualTo(OrderStatus.TRIGGERED);
        assertThat(spawned).isInstanceOf(LimitOrder.class);
        assertThat(((LimitOrder) spawned).getPrice()).isEqualTo(14_800);
        assertThat(spawned.getSide()).isEqualTo(Side.SELL);
        assertThat(spawned.getSequence()).isEqualTo(9);
    }

    @Test
    void triggerUsesRemainingQuantityNotOriginalQuantity() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        TradableOrder spawned = stop.trigger(1);

        assertThat(spawned.getQuantity()).isEqualTo(100);
    }

    @Test
    void cannotTriggerAnAlreadyTriggeredStop() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        stop.trigger(1);

        assertThatThrownBy(() -> stop.trigger(2))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void cannotTriggerACancelledStop() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);
        stop.cancel();

        assertThatThrownBy(() -> stop.trigger(1))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void cancelWorksLikeAnyOtherOrder() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 0);

        stop.cancel();

        assertThat(stop.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stop.isActive()).isFalse();
    }

    @Test
    void toStringContainsKeyFields() {
        StopOrder stop = new StopOrder(1, "AAPL", Side.BUY, 100, 15_000, 15_100, 0);

        assertThat(stop.toString())
                .contains("id=1")
                .contains("stopPrice=15000")
                .contains("limitPrice=15100");
    }
}
