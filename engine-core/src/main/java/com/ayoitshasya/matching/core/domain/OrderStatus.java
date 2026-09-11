package com.ayoitshasya.matching.core.domain;

/**
 * The lifecycle state of an {@link Order}.
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    /**
     * @return true if an order in this status can no longer be filled or cancelled
     */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
