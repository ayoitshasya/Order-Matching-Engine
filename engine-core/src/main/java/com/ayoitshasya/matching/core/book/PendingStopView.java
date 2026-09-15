package com.ayoitshasya.matching.core.book;

import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;

import java.util.OptionalLong;

/**
 * An immutable, point-in-time snapshot of one pending {@link StopOrder}, safe to hand to callers
 * outside the book. See {@link RestingOrderView} for why this is one row per order rather than
 * an aggregated view.
 */
public record PendingStopView(
        long orderId, Side side, long stopPrice, OptionalLong limitPrice, long quantity,
        long remainingQuantity, long sequence) {
}
