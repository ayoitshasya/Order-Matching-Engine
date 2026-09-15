package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Order;
import com.ayoitshasya.matching.core.domain.OrderStatus;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.domain.Trade;
import com.ayoitshasya.matching.core.domain.TradableOrder;
import com.ayoitshasya.matching.core.domain.UnfilledRemainderHandler;
import com.ayoitshasya.matching.core.event.TradeListener;
import com.ayoitshasya.matching.core.exception.InvalidOrderException;
import com.ayoitshasya.matching.core.exception.OrderNotFoundException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A single-symbol order book, matching incoming orders against resting ones under price-time
 * priority.
 *
 * <p>Bids and asks are each a {@link TreeMap} from price to {@link PriceLevel}: bids ordered
 * highest price first, asks lowest price first, so the best price on either side is always the
 * first entry (an O(log n) lookup, not O(1) — {@code TreeMap} walks the tree for it rather than
 * caching it). An incoming order matches while it {@link TradableOrder#crosses(long) crosses}
 * the opposite side's best price; the trade prints at the resting order's price, since the
 * resting order was already quoted at that price and the incoming order is the one crossing the
 * spread to take it.
 *
 * <p>Matching is entirely polymorphic: this class calls {@code crosses} and
 * {@code applyUnfilledRemainder} on whatever {@link TradableOrder} it was given and never
 * branches on its concrete type. {@link StopOrder}s never reach this matching logic at all —
 * they wait in a separate pending-stop structure (see {@link #submitStop}) until triggered.
 *
 * <p>{@code ordersById} gives O(1) lookup for cancellation without a linear scan of price
 * levels. This class is not thread-safe for matching: {@code MatchingEngine} confines all order
 * and cancellation traffic for one symbol to a single writer thread rather than adding locking
 * here. Listener registration is the one operation that can legitimately happen from a different
 * thread (a caller registering interest in a symbol's book at any time), so {@link #listeners} is
 * a {@code CopyOnWriteArrayList} rather than a plain list.
 */
public final class OrderBook {

    private final String symbol;

    private final TreeMap<Long, PriceLevel<LimitOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel<LimitOrder>> asks = new TreeMap<>(Comparator.naturalOrder());
    private final Map<Long, LimitOrder> ordersById = new HashMap<>();

    // Buy stops trigger as price rises, so the lowest stop price is checked first; sell stops
    // trigger as price falls, so the highest is checked first. Same ordering idea as bids/asks.
    private final TreeMap<Long, PriceLevel<StopOrder>> pendingBuyStops = new TreeMap<>(Comparator.naturalOrder());
    private final TreeMap<Long, PriceLevel<StopOrder>> pendingSellStops = new TreeMap<>(Comparator.reverseOrder());
    private final Map<Long, StopOrder> stopOrdersById = new HashMap<>();

    // A CopyOnWriteArrayList, not an ArrayList: registration can happen from a thread other than
    // this book's writer thread (see MatchingEngine), concurrently with that writer thread
    // iterating this list mid-trade. See NOTES-CONCURRENCY.md.
    private final List<TradeListener> listeners = new CopyOnWriteArrayList<>();

    /** Disposes of a TradableOrder's unfilled remainder without OrderBook exposing that as public API. */
    private final UnfilledRemainderHandler remainderHandler = new UnfilledRemainderHandler() {
        @Override
        public void rest(LimitOrder order) {
            sideOf(order.getSide()).computeIfAbsent(order.getPrice(), PriceLevel::new).addOrder(order);
            ordersById.put(order.getId(), order);
        }

        @Override
        public void cancelRemainder(TradableOrder order) {
            order.cancel();
        }
    };

    private OptionalLong lastTradePrice = OptionalLong.empty();
    private long nextTradeId = 1;
    private long nextTradeSequence = 0;
    private long nextSpawnedOrderSequence = 0;

    public OrderBook(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new InvalidOrderException("Order book symbol must not be blank");
        }
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Registers {@code listener} to receive every trade and status change from this book.
     * Registering the same listener instance twice is a no-op, rather than delivering every
     * event to it twice: a caller that registers the same listener against a book it does not
     * yet know exists (see {@code MatchingEngine.addListener}) can safely do so without checking
     * first.
     */
    public void addListener(TradeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Submits an order for immediate matching. {@link LimitOrder} and {@link MarketOrder}
     * both flow through here; each decides for itself how it crosses the book and what
     * happens to any unfilled remainder (see {@link TradableOrder}), and matching may cascade
     * into pending stops (see {@link #processTriggeredStops()}).
     *
     * @return the trades this specific order generated directly, in the order they occurred.
     *         Trades from any stops this order's activity went on to trigger are delivered
     *         only to listeners, not included here — see {@link #processTriggeredStops()}.
     * @throws InvalidOrderException if the order's symbol does not match this book's, or an
     *                                 order with the same ID already exists in this book
     */
    public List<Trade> submit(TradableOrder order) {
        validateNewOrder(order.getSymbol(), order.getId());
        List<Trade> trades = matchAndSettle(order);
        processTriggeredStops();
        return trades;
    }

    /**
     * Parks a stop (or stop-limit) order in the pending stop book. It never enters the visible
     * price levels; it waits here until {@link #processTriggeredStops()} finds that the last
     * trade price has crossed its stop price.
     *
     * @throws InvalidOrderException if the order's symbol does not match this book's, or an
     *                                 order with the same ID already exists in this book
     */
    public void submitStop(StopOrder stopOrder) {
        validateNewOrder(stopOrder.getSymbol(), stopOrder.getId());
        stopSideOf(stopOrder.getSide())
                .computeIfAbsent(stopOrder.getStopPrice(), PriceLevel::new)
                .addOrder(stopOrder);
        stopOrdersById.put(stopOrder.getId(), stopOrder);
    }

    /**
     * Cancels a resting order.
     *
     * @throws OrderNotFoundException if no order with this ID is resting in the book
     */
    public void cancel(long orderId) {
        LimitOrder order = ordersById.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }

        OrderStatus before = order.getStatus();
        order.cancel();
        sideOf(order.getSide()).get(order.getPrice()).removeOrder(orderId);
        removeLevelIfEmpty(sideOf(order.getSide()), order.getPrice());
        ordersById.remove(orderId);
        notifyStatusChangeIfChanged(order, before);
    }

    /**
     * Cancels a pending stop order before it triggers.
     *
     * @throws OrderNotFoundException if no pending stop with this ID exists in this book
     */
    public void cancelStop(long orderId) {
        StopOrder stop = stopOrdersById.get(orderId);
        if (stop == null) {
            throw new OrderNotFoundException(orderId);
        }

        OrderStatus before = stop.getStatus();
        stop.cancel();
        stopSideOf(stop.getSide()).get(stop.getStopPrice()).removeOrder(orderId);
        removeLevelIfEmpty(stopSideOf(stop.getSide()), stop.getStopPrice());
        stopOrdersById.remove(orderId);
        notifyStatusChangeIfChanged(stop, before);
    }

    public OptionalLong bestBidPrice() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAskPrice() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /**
     * @return the best ask minus the best bid, or empty if either side of the book has no orders
     */
    public OptionalLong spread() {
        if (bids.isEmpty() || asks.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(asks.firstKey() - bids.firstKey());
    }

    /** @return up to {@code maxLevels} bid levels, best price first */
    public List<PriceLevelView> bidDepth(int maxLevels) {
        return depth(bids, maxLevels);
    }

    /** @return up to {@code maxLevels} ask levels, best price first */
    public List<PriceLevelView> askDepth(int maxLevels) {
        return depth(asks, maxLevels);
    }

    /** @return the price of the most recent trade in this book, or empty if none has occurred */
    public OptionalLong lastTradePrice() {
        return lastTradePrice;
    }

    /**
     * @return every resting order in this book, bids best-to-worst price then asks
     *         best-to-worst price, FIFO within a price level — the same order matching would
     *         visit them in. One row per order, unlike the aggregated {@link #bidDepth} /
     *         {@link #askDepth}; see {@link RestingOrderView} for why.
     */
    public List<RestingOrderView> restingOrders() {
        List<RestingOrderView> views = new ArrayList<>(ordersById.size());
        collectRestingOrders(bids, views);
        collectRestingOrders(asks, views);
        return views;
    }

    /**
     * @return every pending stop in this book, buy stops (triggering as price rises) then sell
     *         stops (triggering as price falls), in trigger-price then FIFO order within a price.
     */
    public List<PendingStopView> pendingStops() {
        List<PendingStopView> views = new ArrayList<>(stopOrdersById.size());
        collectPendingStops(pendingBuyStops, views);
        collectPendingStops(pendingSellStops, views);
        return views;
    }

    private List<Trade> matchAndSettle(TradableOrder order) {
        List<Trade> trades = match(order);
        if (order.isActive() && order.getRemainingQuantity() > 0) {
            OrderStatus before = order.getStatus();
            order.applyUnfilledRemainder(remainderHandler);
            notifyStatusChangeIfChanged(order, before);
        }
        return trades;
    }

    private List<Trade> match(TradableOrder incoming) {
        // Lazily allocated: most submissions to a healthy two-sided book rest without crossing
        // at all, and that path should cost nothing beyond a reference assignment, not an
        // ArrayList that is immediately discarded empty. See the README's Benchmarks section for
        // whether this measurably helped.
        List<Trade> trades = List.of();
        TreeMap<Long, PriceLevel<LimitOrder>> opposite = sideOf(oppositeOf(incoming.getSide()));

        while (incoming.getRemainingQuantity() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel<LimitOrder>> best = opposite.firstEntry();
            long bestPrice = best.getKey();
            if (!incoming.crosses(bestPrice)) {
                break;
            }

            PriceLevel<LimitOrder> level = best.getValue();
            Iterator<LimitOrder> restingOrders = level.ordersInFifoOrder();
            while (incoming.getRemainingQuantity() > 0 && restingOrders.hasNext()) {
                LimitOrder resting = restingOrders.next();
                long matchedQuantity = Math.min(incoming.getRemainingQuantity(), resting.getRemainingQuantity());

                OrderStatus incomingBefore = incoming.getStatus();
                OrderStatus restingBefore = resting.getStatus();

                incoming.fill(matchedQuantity);
                resting.fill(matchedQuantity);
                level.recordFill(matchedQuantity);

                Trade trade = newTrade(incoming, resting, resting.getPrice(), matchedQuantity);
                if (trades.isEmpty()) {
                    trades = new ArrayList<>();
                }
                trades.add(trade);
                lastTradePrice = OptionalLong.of(trade.price());

                notifyTrade(trade);
                notifyStatusChangeIfChanged(incoming, incomingBefore);
                notifyStatusChangeIfChanged(resting, restingBefore);

                if (!resting.isActive()) {
                    restingOrders.remove();
                    ordersById.remove(resting.getId());
                }
            }

            removeLevelIfEmpty(opposite, bestPrice);
        }

        return trades;
    }

    /**
     * Drains the pending stop book after every trade-generating action: one trade can trigger
     * a stop, and the order spawned from that stop can itself trade and trigger another. Each
     * loop iteration re-reads {@code lastTradePrice}, so a cascade of any length is handled
     * iteratively rather than recursively.
     *
     * <p>This checks for triggers once per top-level {@link #submit}, after that order's own
     * matching has fully run — not after every individual trade within a multi-level sweep. A
     * stop that would have triggered partway through a large sweep still triggers, just once
     * the sweep (or the cascade step that produced it) has finished, rather than interleaved
     * mid-sweep. Real venues interleave more tightly; this is a deliberate simplification
     * documented in the README.
     *
     * <p>Cascaded trades are only ever delivered to {@link TradeListener}s, not returned from
     * the {@link #submit} call that started the cascade: the caller asked to place one order,
     * not to receive every downstream consequence of doing so.
     */
    private void processTriggeredStops() {
        StopOrder triggered;
        while ((triggered = pollTriggeredStop()) != null) {
            OrderStatus before = triggered.getStatus();
            TradableOrder spawned = triggered.trigger(nextSpawnedOrderSequence++);
            notifyStatusChangeIfChanged(triggered, before);
            matchAndSettle(spawned);
        }
    }

    private StopOrder pollTriggeredStop() {
        if (lastTradePrice.isEmpty()) {
            return null;
        }
        long price = lastTradePrice.getAsLong();

        StopOrder candidate = firstPendingStop(pendingBuyStops);
        if (candidate != null && candidate.isTriggeredBy(price)) {
            removePendingStop(pendingBuyStops, candidate);
            return candidate;
        }
        candidate = firstPendingStop(pendingSellStops);
        if (candidate != null && candidate.isTriggeredBy(price)) {
            removePendingStop(pendingSellStops, candidate);
            return candidate;
        }
        return null;
    }

    private static StopOrder firstPendingStop(TreeMap<Long, PriceLevel<StopOrder>> side) {
        Map.Entry<Long, PriceLevel<StopOrder>> best = side.firstEntry();
        return best == null ? null : best.getValue().ordersInFifoOrder().next();
    }

    private void removePendingStop(TreeMap<Long, PriceLevel<StopOrder>> side, StopOrder stop) {
        side.get(stop.getStopPrice()).removeOrder(stop.getId());
        removeLevelIfEmpty(side, stop.getStopPrice());
        stopOrdersById.remove(stop.getId());
    }

    private Trade newTrade(TradableOrder incoming, LimitOrder resting, long price, long quantity) {
        long buyOrderId = incoming.getSide() == Side.BUY ? incoming.getId() : resting.getId();
        long sellOrderId = incoming.getSide() == Side.BUY ? resting.getId() : incoming.getId();
        return new Trade(nextTradeId++, buyOrderId, sellOrderId, price, quantity, nextTradeSequence++);
    }

    private void validateNewOrder(String orderSymbol, long orderId) {
        if (!symbol.equals(orderSymbol)) {
            throw new InvalidOrderException(
                    "Order " + orderId + " has symbol " + orderSymbol + " but this book trades " + symbol);
        }
        if (ordersById.containsKey(orderId) || stopOrdersById.containsKey(orderId)) {
            throw new InvalidOrderException("An order with id " + orderId + " is already in the book");
        }
    }

    private void notifyTrade(Trade trade) {
        for (TradeListener listener : listeners) {
            try {
                listener.onTrade(trade);
            } catch (RuntimeException e) {
                // A listener is an observer, not a participant in matching: a broken market-data
                // feed or metrics hook must never be able to corrupt a trade or stop other
                // listeners from being told about it. See the README's Design Decisions.
                System.err.println("TradeListener " + listener + " threw on onTrade: " + e);
            }
        }
    }

    private void notifyStatusChangeIfChanged(Order order, OrderStatus previousStatus) {
        if (order.getStatus() == previousStatus) {
            return;
        }
        for (TradeListener listener : listeners) {
            try {
                listener.onOrderStatusChanged(order, previousStatus, order.getStatus());
            } catch (RuntimeException e) {
                System.err.println("TradeListener " + listener + " threw on onOrderStatusChanged: " + e);
            }
        }
    }

    private TreeMap<Long, PriceLevel<LimitOrder>> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private TreeMap<Long, PriceLevel<StopOrder>> stopSideOf(Side side) {
        return side == Side.BUY ? pendingBuyStops : pendingSellStops;
    }

    private static Side oppositeOf(Side side) {
        return side == Side.BUY ? Side.SELL : Side.BUY;
    }

    private static <T extends Order> void removeLevelIfEmpty(TreeMap<Long, PriceLevel<T>> side, long price) {
        PriceLevel<T> level = side.get(price);
        if (level != null && level.isEmpty()) {
            side.remove(price);
        }
    }

    private static void collectRestingOrders(TreeMap<Long, PriceLevel<LimitOrder>> side, List<RestingOrderView> out) {
        for (PriceLevel<LimitOrder> level : side.values()) {
            for (LimitOrder order : level.orders()) {
                out.add(new RestingOrderView(order.getId(), order.getSide(), order.getPrice(),
                        order.getQuantity(), order.getRemainingQuantity(), order.getSequence()));
            }
        }
    }

    private static void collectPendingStops(TreeMap<Long, PriceLevel<StopOrder>> side, List<PendingStopView> out) {
        for (PriceLevel<StopOrder> level : side.values()) {
            for (StopOrder stop : level.orders()) {
                out.add(new PendingStopView(stop.getId(), stop.getSide(), stop.getStopPrice(),
                        stop.getLimitPrice(), stop.getQuantity(), stop.getRemainingQuantity(), stop.getSequence()));
            }
        }
    }

    private static List<PriceLevelView> depth(TreeMap<Long, PriceLevel<LimitOrder>> side, int maxLevels) {
        if (maxLevels <= 0) {
            throw new IllegalArgumentException("maxLevels must be positive, got " + maxLevels);
        }

        List<PriceLevelView> levels = new ArrayList<>(Math.min(maxLevels, side.size()));
        for (PriceLevel<LimitOrder> level : side.values()) {
            if (levels.size() == maxLevels) {
                break;
            }
            levels.add(new PriceLevelView(level.getPrice(), level.getTotalQuantity(), level.getOrderCount()));
        }
        return levels;
    }
}
