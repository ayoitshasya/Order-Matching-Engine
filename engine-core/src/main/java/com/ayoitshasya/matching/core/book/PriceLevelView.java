package com.ayoitshasya.matching.core.book;

/**
 * An immutable, point-in-time snapshot of one price level, safe to hand to callers outside the
 * book without exposing the resting orders or the book's internal mutable state.
 */
public record PriceLevelView(long price, long totalQuantity, int orderCount) {
}
