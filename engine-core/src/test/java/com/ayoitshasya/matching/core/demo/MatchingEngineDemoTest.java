package com.ayoitshasya.matching.core.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs the demo exactly as a reader following the README would, so it cannot silently break —
 * the demo has no assertions of its own, but if a future change to the engine made it throw,
 * this test would be the thing that notices.
 */
class MatchingEngineDemoTest {

    @Test
    void mainRunsToCompletionWithoutThrowing() {
        assertThatCode(() -> MatchingEngineDemo.main(new String[0])).doesNotThrowAnyException();
    }
}
