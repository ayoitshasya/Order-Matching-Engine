package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.Order;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All resting orders at a single price, in FIFO (time priority) order.
 *
 * <p>Generic so the same structure backs both the visible book's price levels
 * ({@code PriceLevel<LimitOrder>}) and the pending stop book's trigger-price buckets
 * ({@code PriceLevel<StopOrder>}) — both are "orders waiting at a price, in arrival order,
 * with a running total quantity," just waiting for a different kind of event.
 *
 * <p>Orders are kept in a {@link LinkedHashMap} keyed by order ID: insertion order gives FIFO
 * iteration for time priority, while the map gives O(1) removal by ID once a resting order is
 * fully filled or cancelled. {@code totalQuantity} is maintained incrementally rather than
 * recomputed, since it is read on every depth snapshot.
 *
 * <p>Package-private: callers only ever see a {@link PriceLevelView} snapshot through
 * {@link OrderBook}, never this mutable structure directly.
 */
final class PriceLevel<T extends Order> {

    private final long price;
    private final Map<Long, T> orders = new LinkedHashMap<>();
    private long totalQuantity;

    PriceLevel(long price) {
        this.price = price;
    }

    long getPrice() {
        return price;
    }

    long getTotalQuantity() {
        return totalQuantity;
    }

    int getOrderCount() {
        return orders.size();
    }

    boolean isEmpty() {
        return orders.isEmpty();
    }

    void addOrder(T order) {
        orders.put(order.getId(), order);
        totalQuantity += order.getRemainingQuantity();
    }

    /**
     * Removes an order by ID, e.g. on cancellation. Returns {@code null} if no such order rests
     * at this level.
     */
    T removeOrder(long orderId) {
        T removed = orders.remove(orderId);
        if (removed != null) {
            totalQuantity -= removed.getRemainingQuantity();
        }
        return removed;
    }

    /**
     * Records that {@code filledQuantity} of this level's resting quantity was just matched.
     * The matching loop is responsible for filling the individual order and, once it is fully
     * consumed, removing it via the iterator from {@link #ordersInFifoOrder()}.
     */
    void recordFill(long filledQuantity) {
        totalQuantity -= filledQuantity;
    }

    /**
     * A live iterator over resting orders in FIFO order. Supports {@link Iterator#remove()} so
     * the matching loop can drop orders as they are fully filled without a second lookup.
     */
    Iterator<T> ordersInFifoOrder() {
        return orders.values().iterator();
    }

    /** A read-only view of resting orders in FIFO order, for tests and diagnostics. */
    Collection<T> orders() {
        return Collections.unmodifiableCollection(orders.values());
    }
}
