package com.ayoitshasya.matching.core.eventlog;

import com.ayoitshasya.matching.core.domain.LimitOrder;
import com.ayoitshasya.matching.core.domain.MarketOrder;
import com.ayoitshasya.matching.core.domain.Side;
import com.ayoitshasya.matching.core.domain.StopOrder;
import com.ayoitshasya.matching.core.engine.CancelOrder;
import com.ayoitshasya.matching.core.engine.CancelStopOrder;
import com.ayoitshasya.matching.core.engine.Command;
import com.ayoitshasya.matching.core.engine.PlaceOrder;
import com.ayoitshasya.matching.core.engine.PlaceStopOrder;
import com.ayoitshasya.matching.core.exception.EventLogCorruptionException;

/**
 * Encodes a {@link Command} as one line of text and decodes it back, for the event log.
 *
 * <p>The format is deliberately a hand-rolled, pipe-delimited line rather than a general
 * serialization format: {@code engine-core} carries zero runtime dependencies (see the README),
 * and a matching engine's command vocabulary is small and fixed, so a general-purpose library
 * would buy nothing here. Six line shapes cover the four {@link Command} types — a
 * {@link PlaceOrder} is either a limit or a market order, and a {@link PlaceStopOrder} is either
 * a plain stop or a stop-limit:
 *
 * <pre>
 * PLACE_LIMIT|symbol|orderId|side|quantity|price|sequence
 * PLACE_MARKET|symbol|orderId|side|quantity|sequence
 * PLACE_STOP|symbol|orderId|side|quantity|stopPrice|sequence
 * PLACE_STOP_LIMIT|symbol|orderId|side|quantity|stopPrice|limitPrice|sequence
 * CANCEL|symbol|orderId
 * CANCEL_STOP|symbol|orderId
 * </pre>
 *
 * <p>Symbols are assumed not to contain the {@code |} delimiter, consistent with real exchange
 * tickers (e.g. {@code AAPL}).
 */
public final class EventLogCodec {

    private static final String DELIMITER = "|";

    private EventLogCodec() {
    }

    public static String encode(Command<?> command) {
        return switch (command) {
            case PlaceOrder(LimitOrder order) -> join("PLACE_LIMIT", order.getSymbol(), order.getId(),
                    order.getSide(), order.getQuantity(), order.getPrice(), order.getSequence());
            case PlaceOrder(MarketOrder order) -> join("PLACE_MARKET", order.getSymbol(), order.getId(),
                    order.getSide(), order.getQuantity(), order.getSequence());
            case PlaceStopOrder(StopOrder order) when order.isStopLimit() -> join("PLACE_STOP_LIMIT",
                    order.getSymbol(), order.getId(), order.getSide(), order.getQuantity(),
                    order.getStopPrice(), order.getLimitPrice().getAsLong(), order.getSequence());
            case PlaceStopOrder(StopOrder order) -> join("PLACE_STOP", order.getSymbol(), order.getId(),
                    order.getSide(), order.getQuantity(), order.getStopPrice(), order.getSequence());
            case CancelOrder cancelOrder -> join("CANCEL", cancelOrder.symbol(), cancelOrder.orderId());
            case CancelStopOrder cancelStopOrder ->
                    join("CANCEL_STOP", cancelStopOrder.symbol(), cancelStopOrder.orderId());
        };
    }

    /**
     * @throws EventLogCorruptionException if {@code line} does not match one of the six known
     *                                       shapes, has the wrong number of fields for its type,
     *                                       or has a field that fails to parse
     */
    public static Command<?> decode(String line) {
        String[] fields = line.split("\\" + DELIMITER, -1);
        try {
            return switch (fields[0]) {
                case "PLACE_LIMIT" -> new PlaceOrder(new LimitOrder(
                        Long.parseLong(fields[2]), fields[1], Side.valueOf(fields[3]),
                        Long.parseLong(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6])));
                case "PLACE_MARKET" -> new PlaceOrder(new MarketOrder(
                        Long.parseLong(fields[2]), fields[1], Side.valueOf(fields[3]),
                        Long.parseLong(fields[4]), Long.parseLong(fields[5])));
                case "PLACE_STOP" -> new PlaceStopOrder(new StopOrder(
                        Long.parseLong(fields[2]), fields[1], Side.valueOf(fields[3]),
                        Long.parseLong(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6])));
                case "PLACE_STOP_LIMIT" -> new PlaceStopOrder(new StopOrder(
                        Long.parseLong(fields[2]), fields[1], Side.valueOf(fields[3]),
                        Long.parseLong(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6]),
                        Long.parseLong(fields[7])));
                case "CANCEL" -> new CancelOrder(fields[1], Long.parseLong(fields[2]));
                case "CANCEL_STOP" -> new CancelStopOrder(fields[1], Long.parseLong(fields[2]));
                default -> throw new EventLogCorruptionException("Unknown event log entry type: " + fields[0]);
            };
        } catch (EventLogCorruptionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EventLogCorruptionException("Malformed event log line: " + line, e);
        }
    }

    private static String join(Object... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(DELIMITER);
            }
            line.append(fields[i]);
        }
        return line.toString();
    }
}
