package com.ayoitshasya.matching.core.engine;

/**
 * A request submitted to a {@link MatchingEngine}, always addressed to exactly one symbol's
 * writer thread. {@code T} is the type the corresponding {@code CompletableFuture} completes
 * with once that thread has processed this command.
 *
 * <p>Sealed to the four requests the engine understands: placing a tradable order, placing a
 * stop order, and cancelling either. Each implementation is an immutable record — a
 * self-contained unit of work handed to a single writer thread and never mutated afterward.
 */
public sealed interface Command<T> permits PlaceOrder, PlaceStopOrder, CancelOrder, CancelStopOrder {

    /** The symbol whose writer thread owns this command. */
    String symbol();
}
