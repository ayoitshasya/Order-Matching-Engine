package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.InvalidOrderException;

import java.util.Objects;

/**
 * Base type for all orders accepted by the matching engine.
 *
 * <p>An {@code Order} carries only what every order type has in common: identity, the
 * instrument it trades, its side, its quantity, and its lifecycle status. Order-type-specific
 * data (a limit price, a stop trigger price, and so on) lives on the concrete subclass, so new
 * order types can be added without changing this class.
 *
 * <p>State is only ever mutated through {@link #fill(long)}, {@link #cancel()}, and
 * {@link #reject()}, each of which validates the requested transition. There are no public
 * setters.
 *
 * <p>{@code sequence} is a monotonically increasing sequence number assigned by the engine at
 * intake, not a wall-clock timestamp. It gives orders at the same price a stable, deterministic
 * time priority regardless of clock resolution or skew.
 */
public abstract class Order {

    private final long id;
    private final String symbol;
    private final Side side;
    private final long quantity;
    private final long sequence;

    private long remainingQuantity;
    private OrderStatus status;

    protected Order(long id, String symbol, Side side, long quantity, long sequence) {
        if (id <= 0) {
            throw new InvalidOrderException("Order id must be positive, got " + id);
        }
        if (symbol == null || symbol.isBlank()) {
            throw new InvalidOrderException("Order symbol must not be blank");
        }
        if (side == null) {
            throw new InvalidOrderException("Order side must not be null");
        }
        if (quantity <= 0) {
            throw new InvalidOrderException("Order quantity must be positive, got " + quantity);
        }
        if (sequence < 0) {
            throw new InvalidOrderException("Order sequence must not be negative, got " + sequence);
        }

        this.id = id;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.sequence = sequence;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
    }

    /**
     * Reduces the remaining quantity by {@code fillQuantity}, moving the order to
     * {@link OrderStatus#PARTIALLY_FILLED} or {@link OrderStatus#FILLED} as appropriate.
     *
     * @throws InvalidOrderException if the order is in a terminal status, if
     *                                {@code fillQuantity} is not positive, or if it exceeds the
     *                                remaining quantity
     */
    public final void fill(long fillQuantity) {
        if (status.isTerminal()) {
            throw new InvalidOrderException(
                    "Cannot fill order " + id + " in terminal status " + status);
        }
        if (fillQuantity <= 0) {
            throw new InvalidOrderException(
                    "Fill quantity must be positive, got " + fillQuantity);
        }
        if (fillQuantity > remainingQuantity) {
            throw new InvalidOrderException(
                    "Fill quantity " + fillQuantity + " exceeds remaining quantity "
                            + remainingQuantity + " for order " + id);
        }

        remainingQuantity -= fillQuantity;
        status = remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * Cancels the order, provided it has not already reached a terminal status.
     *
     * @throws InvalidOrderException if the order is already {@link OrderStatus#FILLED},
     *                                {@link OrderStatus#CANCELLED}, or {@link OrderStatus#REJECTED}
     */
    public final void cancel() {
        if (status.isTerminal()) {
            throw new InvalidOrderException(
                    "Cannot cancel order " + id + " in terminal status " + status);
        }
        status = OrderStatus.CANCELLED;
    }

    /**
     * Rejects the order. Only a brand-new order that has never been filled can be rejected.
     *
     * @throws InvalidOrderException if the order is not in {@link OrderStatus#NEW}
     */
    public final void reject() {
        if (status != OrderStatus.NEW) {
            throw new InvalidOrderException(
                    "Cannot reject order " + id + " in status " + status + "; only a new order can be rejected");
        }
        status = OrderStatus.REJECTED;
    }

    public final long getId() {
        return id;
    }

    public final String getSymbol() {
        return symbol;
    }

    public final Side getSide() {
        return side;
    }

    public final long getQuantity() {
        return quantity;
    }

    public final long getRemainingQuantity() {
        return remainingQuantity;
    }

    public final long getFilledQuantity() {
        return quantity - remainingQuantity;
    }

    public final long getSequence() {
        return sequence;
    }

    public final OrderStatus getStatus() {
        return status;
    }

    public final boolean isActive() {
        return !status.isTerminal();
    }

    /**
     * Moves this order directly to a terminal status outside the fill/cancel/reject
     * lifecycle. Used by order types with their own terminal outcome that isn't a fill, a
     * cancel, or a rejection — for example a stop order moving to
     * {@link OrderStatus#TRIGGERED} once its condition is met.
     *
     * @throws InvalidOrderException if the order is already in a terminal status, or if
     *                                 {@code terminalStatus} is not itself terminal
     */
    protected final void moveToTerminalStatus(OrderStatus terminalStatus) {
        if (status.isTerminal()) {
            throw new InvalidOrderException(
                    "Cannot change status of order " + id + " from terminal status " + status);
        }
        if (!terminalStatus.isTerminal()) {
            throw new InvalidOrderException(
                    "moveToTerminalStatus requires a terminal status, got " + terminalStatus);
        }
        status = terminalStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order other)) {
            return false;
        }
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{"
                + "id=" + id
                + ", symbol='" + symbol + '\''
                + ", side=" + side
                + ", quantity=" + quantity
                + ", remainingQuantity=" + remainingQuantity
                + ", sequence=" + sequence
                + ", status=" + status
                + '}';
    }
}
