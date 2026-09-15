package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.Side;

/**
 * An immutable, point-in-time snapshot of one resting {@link LimitOrder}, safe to hand to
 * callers outside the book without exposing the mutable order itself. Unlike
 * {@link PriceLevelView} (one row per price level, aggregated), this is one row per order,
 * in book order (price, then arrival order within a price) — the level of detail needed to
 * compare two books for exact equality, such as an original book against one rebuilt by replay.
 */
public record RestingOrderView(
        long orderId, Side side, long price, long quantity, long remainingQuantity, long sequence) {
}
