package com.meterflow.metering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IngestionPropertiesTest {

    @Autowired IngestionService service;
    @Autowired JdbcTemplate jdbc;

    String externalId;
    long customerId;

    @BeforeEach
    void setUp() {
        externalId = "props_" + UUID.randomUUID();
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", externalId);
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?", Long.class, externalId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM usage_event WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
    }

    @Test
    void propertiesAreStoredEvenWithQuotes() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("region", "asia");
        props.put("note", "say \"hi\"");   // উদ্ধৃতিচিহ্ন ঠিকমতো escape হচ্ছে কি না দেখার জন্য

        BatchResponse r = service.ingest(new BatchRequest(List.of(
                new EventRequest(externalId, "api_calls", BigDecimal.ONE, Instant.now(), "p-1", props))));

        assertEquals(1, r.accepted());
        String region = jdbc.queryForObject(
                "SELECT properties ->> 'region' FROM usage_event WHERE customer_id = ? AND idempotency_key = 'p-1'",
                String.class, customerId);
        String note = jdbc.queryForObject(
                "SELECT properties ->> 'note' FROM usage_event WHERE customer_id = ? AND idempotency_key = 'p-1'",
                String.class, customerId);
        assertEquals("asia", region);
        assertEquals("say \"hi\"", note);
    }

    @Test
    void tooManyPropertiesAreRejected() {
        Map<String, String> props = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) props.put("k" + i, "v");

        BatchResponse r = service.ingest(new BatchRequest(List.of(
                new EventRequest(externalId, "api_calls", BigDecimal.ONE, Instant.now(), "p-2", props))));

        assertEquals(0, r.accepted());
        assertEquals(1, r.rejected());
    }
}