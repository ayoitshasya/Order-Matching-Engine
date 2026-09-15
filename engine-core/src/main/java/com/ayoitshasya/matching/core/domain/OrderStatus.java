package com.ayoitshasya.matching.core.domain;

/**
 * The lifecycle state of an {@link Order}.
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    /** A stop order whose trigger condition has been met and has spawned its live order. */
    TRIGGERED;

    /**
     * @return true if an order in this status can no longer be filled or cancelled
     */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == TRIGGERED;
    }
}
