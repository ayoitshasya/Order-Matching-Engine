package com.ayoitshasya.matching.core.domain;

import com.ayoitshasya.matching.core.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNotFoundExceptionTest {

    @Test
    void messageIncludesTheMissingOrderId() {
        OrderNotFoundException exception = new OrderNotFoundException(42);

        assertThat(exception.getMessage()).contains("42");
    }
}
