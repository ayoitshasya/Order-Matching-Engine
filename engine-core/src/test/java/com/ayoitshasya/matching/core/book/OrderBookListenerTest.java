package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.event.InMemoryTradeRecorder;
import com.ayoitshasya.matching.core.event.TradeListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookListenerTest {

    private static final String SYMBOL = "AAPL";
    private long nextSequence = 0;

    private long seq() {
        return nextSequence++;
    }

    private LimitOrder limit(long id, Side side, long quantity, long price) {
        return new LimitOrder(id, SYMBOL, side, quantity, price, seq());
    }

    @Test
    void listenersAreNotifiedOfEveryTradeInOccurrenceOrder() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(recorder);

        book.submit(limit(1, Side.SELL, 10, 100));
        book.submit(limit(2, Side.SELL, 10, 110));
        List<Trade> trades = book.submit(limit(3, Side.BUY, 20, 110));

        assertThat(recorder.getTrades()).containsExactlyElementsOf(trades);
        assertThat(recorder.getTrades()).extracting(Trade::price).containsExactly(100L, 110L);
    }

    @Test
    void multipleListenersAreNotifiedInRegistrationOrder() {
        OrderBook book = new OrderBook(SYMBOL);
        List<String> callOrder = new ArrayList<>();
        book.addListener(new TaggingListener("A", callOrder));
        book.addListener(new TaggingListener("B", callOrder));

        book.submit(limit(1, Side.SELL, 10, 100));
        book.submit(limit(2, Side.BUY, 10, 100));

        assertThat(callOrder).containsExactly(
                "A:onTrade", "B:onTrade",
                "A:onOrderStatusChanged", "B:onOrderStatusChanged",
                "A:onOrderStatusChanged", "B:onOrderStatusChanged");
    }

    @Test
    void listenersAreNotifiedOfStatusChangesOnFillAndCancel() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(recorder);

        book.submit(limit(1, Side.SELL, 10, 100));
        book.submit(limit(2, Side.BUY, 4, 100));
        book.submit(limit(3, Side.BUY, 6, 100));
        book.submit(limit(4, Side.SELL, 5, 200));
        book.cancel(4);

        assertThat(recorder.getStatusChanges()).containsExactly(
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.FILLED),
                new InMemoryTradeRecorder.StatusChange(1, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED),
                new InMemoryTradeRecorder.StatusChange(3, OrderStatus.NEW, OrderStatus.FILLED),
                new InMemoryTradeRecorder.StatusChange(1, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED),
                new InMemoryTradeRecorder.StatusChange(4, OrderStatus.NEW, OrderStatus.CANCELLED));
    }

    @Test
    void aFailingListenerDoesNotPreventOtherListenersOrBreakMatching() {
        OrderBook book = new OrderBook(SYMBOL);
        InMemoryTradeRecorder recorder = new InMemoryTradeRecorder();
        book.addListener(new AlwaysThrowsListener());
        book.addListener(recorder);

        book.submit(limit(1, Side.SELL, 10, 100));
        List<Trade> resultTrades = book.submit(limit(2, Side.BUY, 10, 100));

        assertThat(resultTrades).hasSize(1);
        assertThat(recorder.getTrades()).hasSize(1);
        assertThat(recorder.getStatusChanges()).contains(
                new InMemoryTradeRecorder.StatusChange(1, OrderStatus.NEW, OrderStatus.FILLED),
                new InMemoryTradeRecorder.StatusChange(2, OrderStatus.NEW, OrderStatus.FILLED));
        assertThat(book.bestBidPrice()).isEmpty();
        assertThat(book.bestAskPrice()).isEmpty();
    }

    private static final class TaggingListener implements TradeListener {
        private final String tag;
        private final List<String> callOrder;

        private TaggingListener(String tag, List<String> callOrder) {
            this.tag = tag;
            this.callOrder = callOrder;
        }

        @Override
        public void onTrade(Trade trade) {
            callOrder.add(tag + ":onTrade");
        }

        @Override
        public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
            callOrder.add(tag + ":onOrderStatusChanged");
        }
    }

    private static final class AlwaysThrowsListener implements TradeListener {
        @Override
        public void onTrade(Trade trade) {
            throw new RuntimeException("boom: onTrade");
        }

        @Override
        public void onOrderStatusChanged(Order order, OrderStatus previousStatus, OrderStatus newStatus) {
            throw new RuntimeException("boom: onOrderStatusChanged");
        }
    }
}
