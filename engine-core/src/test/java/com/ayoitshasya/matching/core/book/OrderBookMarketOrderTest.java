package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.MarketOrder;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookMarketOrderTest {

    private static final String SYMBOL = "AAPL";
    private long nextSequence = 0;

    private long seq() {
        return nextSequence++;
    }

    private LimitOrder limit(long id, Side side, long quantity, long price) {
        return new LimitOrder(id, SYMBOL, side, quantity, price, seq());
    }

    private MarketOrder market(long id, Side side, long quantity) {
        return new MarketOrder(id, SYMBOL, side, quantity, seq());
    }

    @Test
    void marketOrderOnEmptyBookIsCancelledNotAnError() {
        OrderBook book = new OrderBook(SYMBOL);
        MarketOrder order = market(1, Side.BUY, 100);

        List<Trade> trades = book.submit(order);

        assertThat(trades).isEmpty();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> book.cancel(1)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void marketOrderMatchesAtRestingPricesRegardlessOfHowFarTheyCross() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 30, 15_000));
        book.submit(limit(2, Side.SELL, 30, 20_000));

        MarketOrder order = market(3, Side.BUY, 60);
        List<Trade> trades = book.submit(order);

        assertThat(trades).hasSize(2);
        assertThat(trades).extracting(Trade::price).containsExactly(15_000L, 20_000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void marketOrderPartiallyFilledThenRemainderIsCancelled() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 30, 15_000));

        MarketOrder order = market(2, Side.BUY, 100);
        List<Trade> trades = book.submit(order);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).quantity()).isEqualTo(30);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFilledQuantity()).isEqualTo(30);
        assertThat(order.getRemainingQuantity()).isEqualTo(70);
    }

    @Test
    void marketOrderNeverRestsEvenWhenUnfilled() {
        OrderBook book = new OrderBook(SYMBOL);
        MarketOrder order = market(1, Side.SELL, 100);

        book.submit(order);

        assertThat(book.bestBidPrice()).isEmpty();
        assertThat(book.bestAskPrice()).isEmpty();
        assertThatThrownBy(() -> book.cancel(1)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void marketSellOrderMatchesAgainstRestingBids() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 30, 15_200));
        book.submit(limit(2, Side.BUY, 30, 15_000));

        MarketOrder order = market(3, Side.SELL, 40);
        List<Trade> trades = book.submit(order);

        assertThat(trades).hasSize(2);
        assertThat(trades.get(0).price()).isEqualTo(15_200);
        assertThat(trades.get(0).quantity()).isEqualTo(30);
        assertThat(trades.get(1).price()).isEqualTo(15_000);
        assertThat(trades.get(1).quantity()).isEqualTo(10);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }
}
