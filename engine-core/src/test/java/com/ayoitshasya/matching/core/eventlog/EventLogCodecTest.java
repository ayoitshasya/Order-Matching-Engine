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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips every line shape through {@link EventLogCodec#encode} then {@link
 * EventLogCodec#decode}, asserting on the decoded order's individual fields rather than on
 * {@code Command}/{@code Order} equality — {@code Order.equals} is ID-based only (see its
 * class docs), so it would not actually catch a codec bug that dropped or swapped a field.
 */
class EventLogCodecTest {

    @Test
    void roundTripsALimitOrder() {
        PlaceOrder original = new PlaceOrder(new LimitOrder(1, "AAPL", Side.BUY, 10, 150, 7));

        PlaceOrder decoded = (PlaceOrder) EventLogCodec.decode(EventLogCodec.encode(original));
        LimitOrder order = (LimitOrder) decoded.order();

        assertThat(order.getId()).isEqualTo(1);
        assertThat(order.getSymbol()).isEqualTo("AAPL");
        assertThat(order.getSide()).isEqualTo(Side.BUY);
        assertThat(order.getQuantity()).isEqualTo(10);
        assertThat(order.getPrice()).isEqualTo(150);
        assertThat(order.getSequence()).isEqualTo(7);
    }

    @Test
    void roundTripsAMarketOrder() {
        PlaceOrder original = new PlaceOrder(new MarketOrder(2, "MSFT", Side.SELL, 20, 8));

        PlaceOrder decoded = (PlaceOrder) EventLogCodec.decode(EventLogCodec.encode(original));
        MarketOrder order = (MarketOrder) decoded.order();

        assertThat(order.getId()).isEqualTo(2);
        assertThat(order.getSymbol()).isEqualTo("MSFT");
        assertThat(order.getSide()).isEqualTo(Side.SELL);
        assertThat(order.getQuantity()).isEqualTo(20);
        assertThat(order.getSequence()).isEqualTo(8);
    }

    @Test
    void roundTripsAPlainStopOrder() {
        PlaceStopOrder original = new PlaceStopOrder(new StopOrder(3, "AAPL", Side.BUY, 5, 200, 9));

        PlaceStopOrder decoded = (PlaceStopOrder) EventLogCodec.decode(EventLogCodec.encode(original));
        StopOrder order = decoded.order();

        assertThat(order.getId()).isEqualTo(3);
        assertThat(order.getSymbol()).isEqualTo("AAPL");
        assertThat(order.getSide()).isEqualTo(Side.BUY);
        assertThat(order.getQuantity()).isEqualTo(5);
        assertThat(order.getStopPrice()).isEqualTo(200);
        assertThat(order.isStopLimit()).isFalse();
        assertThat(order.getSequence()).isEqualTo(9);
    }

    @Test
    void roundTripsAStopLimitOrder() {
        PlaceStopOrder original = new PlaceStopOrder(new StopOrder(4, "AAPL", Side.SELL, 6, 210, 205, 11));

        PlaceStopOrder decoded = (PlaceStopOrder) EventLogCodec.decode(EventLogCodec.encode(original));
        StopOrder order = decoded.order();

        assertThat(order.getId()).isEqualTo(4);
        assertThat(order.getSymbol()).isEqualTo("AAPL");
        assertThat(order.getSide()).isEqualTo(Side.SELL);
        assertThat(order.getQuantity()).isEqualTo(6);
        assertThat(order.getStopPrice()).isEqualTo(210);
        assertThat(order.isStopLimit()).isTrue();
        assertThat(order.getLimitPrice()).hasValue(205);
        assertThat(order.getSequence()).isEqualTo(11);
    }

    @Test
    void roundTripsACancelOrder() {
        CancelOrder original = new CancelOrder("AAPL", 42);

        Command<?> decoded = EventLogCodec.decode(EventLogCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTripsACancelStopOrder() {
        CancelStopOrder original = new CancelStopOrder("MSFT", 43);

        Command<?> decoded = EventLogCodec.decode(EventLogCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void decodingAnUnknownEntryTypeThrowsEventLogCorruptionException() {
        assertThatThrownBy(() -> EventLogCodec.decode("NOT_A_REAL_TYPE|AAPL|1"))
                .isInstanceOf(EventLogCorruptionException.class);
    }

    @Test
    void decodingALineWithMissingFieldsThrowsEventLogCorruptionException() {
        assertThatThrownBy(() -> EventLogCodec.decode("PLACE_LIMIT|AAPL|1"))
                .isInstanceOf(EventLogCorruptionException.class);
    }

    @Test
    void decodingALineWithAnUnparsableFieldThrowsEventLogCorruptionException() {
        assertThatThrownBy(() -> EventLogCodec.decode("CANCEL|AAPL|not-a-number"))
                .isInstanceOf(EventLogCorruptionException.class);
    }
}
