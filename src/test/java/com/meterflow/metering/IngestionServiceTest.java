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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IngestionServiceTest {

    @Autowired IngestionService service;
    @Autowired JdbcTemplate jdbc;

    String externalId;   // প্রতি test-এ নতুন customer, তাই আগের ডেটার সাথে মিলে যাবে না

    @BeforeEach
    void createCustomer() {
        externalId = "test_" + UUID.randomUUID();
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", externalId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM usage_event WHERE customer_id = (SELECT id FROM customer WHERE external_id = ?)", externalId);
        jdbc.update("DELETE FROM customer WHERE external_id = ?", externalId);
    }

    private EventRequest event(String key, String qty) {
        return new EventRequest(externalId, "api_calls", new BigDecimal(qty), Instant.now(), key);
    }

    private long countEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE customer_id = (SELECT id FROM customer WHERE external_id = ?)",
                Long.class, externalId);
    }

    @Test
    void duplicateInsideSameBatchIsStoredOnce() {
        BatchResponse r = service.ingest(new BatchRequest(List.of(event("a", "1"), event("a", "1"), event("b", "2"))));
        assertEquals(2, r.accepted());
        assertEquals(1, r.duplicates());
        assertEquals(2, countEvents());
    }

    @Test
    void resendingSameBatchAddsNothing() {
        BatchRequest batch = new BatchRequest(List.of(event("a", "1"), event("b", "2")));
        service.ingest(batch);
        BatchResponse second = service.ingest(batch);
        assertEquals(0, second.accepted());
        assertEquals(2, second.duplicates());
        assertEquals(2, countEvents());
    }

    @Test
    void badEventsAreRejectedButGoodOnesStay() {
        BatchResponse r = service.ingest(new BatchRequest(List.of(
                event("a", "1"),
                event("b", "-5"),                                   // ঋণাত্মক quantity
                new EventRequest("nobody", "api_calls", BigDecimal.ONE, Instant.now(), "c"))));
        assertEquals(1, r.accepted());
        assertEquals(2, r.rejected());
    }

    @Test
    void moreThan1000EventsIsRefused() {
        List<EventRequest> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add(event("k" + i, "1"));
        assertThrows(ResponseStatusException.class, () -> service.ingest(new BatchRequest(many)));
    }
}