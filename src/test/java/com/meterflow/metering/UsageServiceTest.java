package com.meterflow.metering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UsageServiceTest {

    @Autowired IngestionService ingestion;
    @Autowired MetricService metrics;
    @Autowired UsageService usage;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    static final LocalDate TO = LocalDate.of(2026, 10, 31);

    String customer;
    long customerId;
    String eventName;
    final List<String> metricCodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        customer = "usage_" + UUID.randomUUID();
        eventName = "ev_" + UUID.randomUUID();
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", customer);
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?", Long.class, customer);

        // অক্টোবরে ৪টা event, নভেম্বরের ১ তারিখে ১টা (সেটা হিসাবে আসা উচিত নয়)
        BatchResponse r = ingestion.ingest(new BatchRequest(List.of(
                ev("e1", "10",  "2026-10-05T10:00:00Z", "asia",   "u1"),
                ev("e2", "5",   "2026-10-10T10:00:00Z", "asia",   "u2"),
                ev("e3", "7",   "2026-10-15T10:00:00Z", "europe", "u1"),
                ev("e4", "3",   "2026-10-31T23:59:00Z", "asia",   "u3"),
                ev("e5", "100", "2026-11-01T00:00:00Z", "asia",   "u4"))));
        assertEquals(5, r.accepted());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM usage_event WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        for (String code : metricCodes) {
            jdbc.update("DELETE FROM billable_metric WHERE code = ?", code);   // filter গুলো নিজে মুছে যায়
        }
    }

    private EventRequest ev(String key, String qty, String at, String region, String user) {
        return new EventRequest(customer, eventName, new BigDecimal(qty), Instant.parse(at), key,
                Map.of("region", region, "user", user));
    }

    private UsageResult run(String aggregation, String property, Map<String, String> filters) {
        String code = "m_" + UUID.randomUUID();
        metricCodes.add(code);
        metrics.create(new MetricDefinition(null, code, "Test " + aggregation, aggregation, property, eventName, filters));
        return usage.compute(customer, code, FROM, TO);
    }

    private static void assertValue(String expected, UsageResult r) {
        assertEquals(0, new BigDecimal(expected).compareTo(r.value()), "value was " + r.value());
    }

    @Test
    void sumAddsAllQuantitiesInThePeriod() {
        UsageResult r = run("SUM", null, null);
        assertValue("25", r);          // 10 + 5 + 7 + 3, নভেম্বরের 100 বাদ
        assertEquals(4, r.eventCount());
    }

    @Test
    void countCountsEvents() {
        assertValue("4", run("COUNT", null, null));
    }

    @Test
    void maxFindsLargestQuantity() {
        assertValue("10", run("MAX", null, null));
    }

    @Test
    void uniqueCountCountsDistinctPropertyValues() {
        assertValue("3", run("UNIQUE_COUNT", "user", null));   // u1, u2, u3
    }

    @Test
    void latestTakesTheNewestEvent() {
        assertValue("3", run("LATEST", null, null));           // e4 সবচেয়ে নতুন
    }

    @Test
    void filterKeepsOnlyMatchingEvents() {
        UsageResult r = run("SUM", null, Map.of("region", "asia"));
        assertValue("18", r);          // 10 + 5 + 3
        assertEquals(3, r.eventCount());
    }

    @Test
    void unknownAggregationIsRejected() {
        assertThrows(ResponseStatusException.class, () -> metrics.create(
                new MetricDefinition(null, "bad_" + UUID.randomUUID(), "x", "MEDIAN", null, eventName, null)));
    }

    @Test
    void uniqueCountWithoutPropertyIsRejected() {
        assertThrows(ResponseStatusException.class, () -> metrics.create(
                new MetricDefinition(null, "bad_" + UUID.randomUUID(), "x", "UNIQUE_COUNT", null, eventName, null)));
    }

    @Test
    void unknownMetricIsNotFound() {
        assertThrows(ResponseStatusException.class,
                () -> usage.compute(customer, "nope_" + UUID.randomUUID(), FROM, TO));
    }
}