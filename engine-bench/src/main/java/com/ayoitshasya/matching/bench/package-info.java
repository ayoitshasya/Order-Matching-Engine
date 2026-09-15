/**
 * JMH benchmarks for the order matching engine: throughput and latency percentiles for both the
 * single-threaded {@code OrderBook} and the multithreaded {@code MatchingEngine}, at varying book
 * depths. See the README's Benchmarks section for how to run these and the last measured numbers.
 */
package com.ayoitshasya.matching.bench;
