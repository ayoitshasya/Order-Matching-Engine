package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import com.ayoitshasya.matching.core.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookTest {

    private static final String SYMBOL = "AAPL";
    private long nextSequence = 0;

    private long seq() {
        return nextSequence++;
    }

    private LimitOrder limit(long id, Side side, long quantity, long price) {
        return new LimitOrder(id, SYMBOL, side, quantity, price, seq());
    }

    @Test
    void emptyBookHasNoBestPricesOrSpread() {
        OrderBook book = new OrderBook(SYMBOL);

        assertThat(book.bestBidPrice()).isEmpty();
        assertThat(book.bestAskPrice()).isEmpty();
        assertThat(book.spread()).isEmpty();
        assertThat(book.bidDepth(5)).isEmpty();
        assertThat(book.askDepth(5)).isEmpty();
    }

    @Test
    void nonCrossingOrderJustRestsInTheBook() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder buy = limit(1, Side.BUY, 100, 15_000);

        List<Trade> trades = book.submit(buy);

        assertThat(trades).isEmpty();
        assertThat(buy.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(book.bestBidPrice()).hasValue(15_000);
        assertThat(book.bestAskPrice()).isEmpty();
    }

    @Test
    void ordersOnBothSidesThatDoNotCrossBothRest() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 100, 14_900));
        book.submit(limit(2, Side.SELL, 100, 15_100));

        assertThat(book.bestBidPrice()).hasValue(14_900);
        assertThat(book.bestAskPrice()).hasValue(15_100);
        assertThat(book.spread()).hasValue(200);
    }

    @Test
    void exactMatchFullyFillsBothOrdersAndEmptiesTheLevel() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder resting = limit(1, Side.SELL, 100, 15_000);
        book.submit(resting);

        LimitOrder incoming = limit(2, Side.BUY, 100, 15_000);
        List<Trade> trades = book.submit(incoming);

        assertThat(trades).hasSize(1);
        Trade trade = trades.get(0);
        assertThat(trade.buyOrderId()).isEqualTo(2);
        assertThat(trade.sellOrderId()).isEqualTo(1);
        assertThat(trade.price()).isEqualTo(15_000);
        assertThat(trade.quantity()).isEqualTo(100);

        assertThat(resting.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(incoming.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(book.bestBidPrice()).isEmpty();
        assertThat(book.bestAskPrice()).isEmpty();
    }

    @Test
    void tradePrintsAtTheRestingOrdersPriceNotTheIncomingLimitPrice() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 100, 14_800));

        List<Trade> trades = book.submit(limit(2, Side.BUY, 100, 15_000));

        assertThat(trades).singleElement().satisfies(trade -> assertThat(trade.price()).isEqualTo(14_800));
    }

    @Test
    void partialFillOnRestingOrderLeavesRemainderInBook() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder resting = limit(1, Side.SELL, 100, 15_000);
        book.submit(resting);

        LimitOrder incoming = limit(2, Side.BUY, 40, 15_000);
        List<Trade> trades = book.submit(incoming);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).quantity()).isEqualTo(40);
        assertThat(incoming.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(resting.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(resting.getRemainingQuantity()).isEqualTo(60);

        assertThat(book.bestAskPrice()).hasValue(15_000);
        assertThat(book.askDepth(1)).containsExactly(new PriceLevelView(15_000, 60, 1));
    }

    @Test
    void partialFillOnIncomingOrderRestsTheRemainderAtItsOwnPrice() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder resting = limit(1, Side.SELL, 40, 15_000);
        book.submit(resting);

        LimitOrder incoming = limit(2, Side.BUY, 100, 15_000);
        List<Trade> trades = book.submit(incoming);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).quantity()).isEqualTo(40);
        assertThat(resting.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(incoming.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(incoming.getRemainingQuantity()).isEqualTo(60);

        assertThat(book.bestAskPrice()).isEmpty();
        assertThat(book.bestBidPrice()).hasValue(15_000);
        assertThat(book.bidDepth(1)).containsExactly(new PriceLevelView(15_000, 60, 1));
    }

    @Test
    void incomingOrderSweepsMultiplePriceLevelsBestPriceFirst() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 30, 15_000));
        book.submit(limit(2, Side.SELL, 30, 15_100));
        book.submit(limit(3, Side.SELL, 30, 15_200));

        LimitOrder incoming = limit(4, Side.BUY, 70, 15_200);
        List<Trade> trades = book.submit(incoming);

        assertThat(trades).hasSize(3);
        assertThat(trades).extracting(Trade::price).containsExactly(15_000L, 15_100L, 15_200L);
        assertThat(trades).extracting(Trade::quantity).containsExactly(30L, 30L, 10L);

        assertThat(book.bestAskPrice()).hasValue(15_200);
        assertThat(book.askDepth(5)).containsExactly(new PriceLevelView(15_200, 20, 1));
        assertThat(incoming.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void sweepStopsAtFirstPriceThatNoLongerCrosses() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 30, 15_000));
        book.submit(limit(2, Side.SELL, 30, 15_200));

        LimitOrder incoming = limit(3, Side.BUY, 100, 15_000);
        List<Trade> trades = book.submit(incoming);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).price()).isEqualTo(15_000);
        assertThat(incoming.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(incoming.getRemainingQuantity()).isEqualTo(70);
        assertThat(book.bestAskPrice()).hasValue(15_200);
        assertThat(book.bestBidPrice()).hasValue(15_000);
    }

    @Test
    void restingOrdersAtSamePriceMatchInTimePriorityOrder() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder firstResting = limit(1, Side.SELL, 30, 15_000);
        LimitOrder secondResting = limit(2, Side.SELL, 30, 15_000);
        book.submit(firstResting);
        book.submit(secondResting);

        List<Trade> trades = book.submit(limit(3, Side.BUY, 40, 15_000));

        assertThat(trades).hasSize(2);
        assertThat(trades.get(0).sellOrderId()).isEqualTo(1);
        assertThat(trades.get(0).quantity()).isEqualTo(30);
        assertThat(trades.get(1).sellOrderId()).isEqualTo(2);
        assertThat(trades.get(1).quantity()).isEqualTo(10);

        assertThat(firstResting.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(secondResting.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(secondResting.getRemainingQuantity()).isEqualTo(20);
    }

    @Test
    void sellOrderMatchesAgainstRestingBidsSymmetrically() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 30, 15_200));
        book.submit(limit(2, Side.BUY, 30, 15_000));

        List<Trade> trades = book.submit(limit(3, Side.SELL, 40, 15_000));

        assertThat(trades).hasSize(2);
        assertThat(trades.get(0).price()).isEqualTo(15_200);
        assertThat(trades.get(0).quantity()).isEqualTo(30);
        assertThat(trades.get(1).price()).isEqualTo(15_000);
        assertThat(trades.get(1).quantity()).isEqualTo(10);
    }

    @Test
    void cancelRemovesARestingOrderAndItsEmptyLevel() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder order = limit(1, Side.BUY, 100, 15_000);
        book.submit(order);

        book.cancel(1);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.bestBidPrice()).isEmpty();
        assertThat(book.bidDepth(5)).isEmpty();
    }

    @Test
    void cancelLeavesOtherOrdersAtTheLevelUntouched() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 100, 15_000));
        book.submit(limit(2, Side.BUY, 50, 15_000));

        book.cancel(1);

        assertThat(book.bidDepth(1)).containsExactly(new PriceLevelView(15_000, 50, 1));
    }

    @Test
    void cancellingAnUnknownOrderThrows() {
        OrderBook book = new OrderBook(SYMBOL);

        assertThatThrownBy(() -> book.cancel(999))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void cancellingAlreadyCancelledOrderThrows() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 100, 15_000));
        book.cancel(1);

        assertThatThrownBy(() -> book.cancel(1))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void fullyFilledOrderCannotBeCancelledBecauseItNeverRested() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.SELL, 100, 15_000));
        book.submit(limit(2, Side.BUY, 100, 15_000));

        assertThatThrownBy(() -> book.cancel(2))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void rejectsOrderForADifferentSymbol() {
        OrderBook book = new OrderBook(SYMBOL);
        LimitOrder wrongSymbol = new LimitOrder(1, "MSFT", Side.BUY, 100, 15_000, seq());

        assertThatThrownBy(() -> book.submit(wrongSymbol))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("MSFT");
    }

    @Test
    void rejectsDuplicateOrderId() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 100, 15_000));

        assertThatThrownBy(() -> book.submit(limit(1, Side.SELL, 50, 15_500)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("1");
    }

    @Test
    void depthSnapshotReturnsOnlyTopNLevelsBestFirst() {
        OrderBook book = new OrderBook(SYMBOL);
        book.submit(limit(1, Side.BUY, 10, 14_900));
        book.submit(limit(2, Side.BUY, 20, 15_000));
        book.submit(limit(3, Side.BUY, 30, 14_800));

        List<PriceLevelView> depth = book.bidDepth(2);

        assertThat(depth).containsExactly(
                new PriceLevelView(15_000, 20, 1),
                new PriceLevelView(14_900, 10, 1));
    }

    @Test
    void depthWithNonPositiveMaxLevelsThrows() {
        OrderBook book = new OrderBook(SYMBOL);

        assertThatThrownBy(() -> book.bidDepth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book.askDepth(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSymbol() {
        assertThatThrownBy(() -> new OrderBook(" "))
                .isInstanceOf(InvalidOrderException.class);
    }
}
