package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.InMemoryTradeRecorder;
import com.ayoitshasya.matching.core.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookStopOrderTest {

    private static final String SYMBOL = "AAPL";
    private long nextSequence = 0;

    private long seq() {
        return nextSequence++;
    }

    private LimitOrder limit(long id, Side side, long quantity, long price) {
        return new LimitOrder(id, SYMBOL, side, quantity, price, seq());
    }

    private StopOrder stop(long id, Side side, long quantity, long stopPrice) {
        return new StopOrder(id, SYMBOL, side, quantity, stopPrice, seq());
    }

    @Test
    void pendingStopDoesNotAppearInVisibleBookDepth() {
        OrderBook book = new OrderBook(SYMBOL);

        book.submitStop(stop(1, Side.BUY, 100, 15_000));

        assertThat(book.bestBidPrice()).isEmpty();
        assertThat(book.bidDepth(5)).isEmpty();
    }

    @Test
    void buyStopTriggersIntoAMarketOrderThatIsCancelledWhenNoLiquidityRemains() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(recorder);

        book.submit(limit(1, Side.SELL, 10, 15_000));
        book.submitStop(stop(2, Side.BUY, 10, 15_000));

        book.submit(limit(3, Side.BUY, 10, 15_000));

        assertThat(recorder.getStatusChanges()).contains(
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.TRIGGERED),
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.CANCELLED));
        assertThatThrownBy(() -> book.cancelStop(2)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void sellStopTriggersIntoAMarketOrderThatIsCancelledWhenNoLiquidityRemains() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(recorder);

        book.submit(limit(1, Side.BUY, 10, 15_000));
        book.submitStop(stop(2, Side.SELL, 10, 15_000));

        book.submit(limit(3, Side.SELL, 10, 15_000));

        assertThat(recorder.getStatusChanges()).contains(
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.TRIGGERED),
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.CANCELLED));
    }

    @Test
    void stopDoesNotTriggerWhenPriceConditionIsUnmet() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 10, 15_000));
        book.submitStop(stop(2, Side.BUY, 10, 15_100));

        book.submit(limit(3, Side.BUY, 10, 15_000));

        assertThatCode(() -> book.cancelStop(2)).doesNotThrowAnyException();
    }

    @Test
    void stopLimitTriggersIntoALimitOrderThatRestsAtItsLimitPrice() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 10, 15_000));
        book.submitStop(new StopOrder(2, SYMBOL, Side.BUY, 10, 15_000, 15_050, seq()));

        book.submit(limit(3, Side.BUY, 10, 15_000));

        assertThat(book.bestBidPrice()).hasValue(15_050);
        assertThat(book.bidDepth(1)).containsExactly(new PriceLevelView(15_050, 10, 1));
    }

    @Test
    void cascadingStopsTriggerEachOtherAcrossMultipleTrades() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(recorder);

        book.submit(limit(1, Side.SELL, 10, 100));
        book.submit(limit(3, Side.SELL, 10, 110));
        book.submit(limit(5, Side.SELL, 10, 120));
        book.submitStop(stop(2, Side.BUY, 10, 100));
        book.submitStop(stop(4, Side.BUY, 10, 110));

        List<Trade> directTrades = book.submit(limit(10, Side.BUY, 10, 100));

        assertThat(directTrades).hasSize(1);
        assertThat(directTrades.get(0).price()).isEqualTo(100);

        assertThat(recorder.getTrades()).extracting(Trade::price)
                .containsExactly(100L, 110L, 120L);
        assertThat(recorder.getStatusChanges()).contains(
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.TRIGGERED),
                new InMemoryTradeRecorder.StatusChange(4, OrderStatus.NEW, OrderStatus.TRIGGERED));
        assertThat(book.bestAskPrice()).isEmpty();
    }

    @Test
    void cancellingAPendingStopRemovesItFromTheStopBook() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submitStop(stop(1, Side.BUY, 100, 15_000));

        book.cancelStop(1);

        assertThatThrownBy(() -> book.cancelStop(1)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancelledStopNeverTriggersEvenIfPriceConditionIsLaterMet() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(recorder);

        book.submit(limit(1, Side.SELL, 10, 15_000));
        StopOrder pending = stop(2, Side.BUY, 10, 15_000);
        book.submitStop(pending);
        book.cancelStop(2);

        book.submit(limit(3, Side.BUY, 10, 15_000));

        assertThat(pending.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(recorder.getStatusChanges())
                .noneMatch(change -> change.orderId() == 2 && change.newStatus() == OrderStatus.TRIGGERED);
    }

    @Test
    void cancellingAnUnknownStopThrows() {
        OrderBook book = new OrderBook(SYMBOL);

        assertThatThrownBy(() -> book.cancelStop(999))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("999");
    }
}
