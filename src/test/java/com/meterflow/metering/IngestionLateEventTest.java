package com.meterflow.metering;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IngestionLateEventTest {

    @Autowired IngestionService service;
    @Autowired JdbcTemplate jdbc;

    String externalId;
    String planCode;
    long customerId;

    @BeforeEach
    void setUp() {
        externalId = "late_" + UUID.randomUUID();
        planCode = "plan_" + UUID.randomUUID();
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", externalId);
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?", Long.class, externalId);
    }

    @AfterEach
    void cleanUp() {
        // foreign key-এর কারণে উল্টো ক্রমে মুছতে হয়: event, invoice, subscription, plan, customer
        jdbc.update("DELETE FROM usage_event WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM invoice WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM subscription WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM plan WHERE code = ?", planCode);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
    }

    /** নকল একটা finalised invoice বসায়, যাতে ওই period "বন্ধ" ধরা হয়। */
    private void createInvoice(LocalDate periodStart, LocalDate periodEnd) {
        long planId = jdbc.queryForObject(
                "INSERT INTO plan (code, name, version, currency) VALUES (?, 'T', 1, 'BDT') RETURNING id",
                Long.class, planCode);
        long subId = jdbc.queryForObject(
                "INSERT INTO subscription (customer_id, plan_id, status, current_period_start, current_period_end) "
                        + "VALUES (?, ?, 'ACTIVE', ?, ?) RETURNING id",
                Long.class, customerId, planId, Date.valueOf(periodStart), Date.valueOf(periodEnd));
        jdbc.update("INSERT INTO invoice (customer_id, subscription_id, period_start, period_end, status, currency) "
                        + "VALUES (?, ?, ?, ?, 'FINALISED', 'BDT')",
                customerId, subId, Date.valueOf(periodStart), Date.valueOf(periodEnd));
    }

    private EventRequest eventAt(Instant when, String key) {
        return new EventRequest(externalId, "api_calls", BigDecimal.TEN, when, key);
    }

    private Instant startOfDayUtc(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Test
    void eventForRecentlyClosedPeriodIsAcceptedAsLate() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // period গতকাল শেষ হয়েছে, মানে ৭২ ঘণ্টার মধ্যেই আছি
        createInvoice(today.minusDays(5), today.minusDays(1));

        BatchResponse r = service.ingest(new BatchRequest(
                List.of(eventAt(startOfDayUtc(today.minusDays(2)).plusSeconds(3600), "late-1"))));

        assertEquals(1, r.accepted());
        assertEquals(1, r.late());
        assertEquals(0, r.rejected());
        Boolean flag = jdbc.queryForObject(
                "SELECT late FROM usage_event WHERE customer_id = ? AND idempotency_key = 'late-1'",
                Boolean.class, customerId);
        assertTrue(flag);
    }

    @Test
    void eventForPeriodClosedLongAgoIsRejected() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // period ১০ দিন আগে শেষ, ৭২ ঘণ্টা অনেক আগে পার
        createInvoice(today.minusDays(20), today.minusDays(10));

        BatchResponse r = service.ingest(new BatchRequest(
                List.of(eventAt(startOfDayUtc(today.minusDays(15)).plusSeconds(3600), "old-1"))));

        assertEquals(0, r.accepted());
        assertEquals(1, r.rejected());
        assertTrue(r.errors().get(0).reason().contains("too late"));
    }

    @Test
    void eventOutsideInvoicedPeriodIsNormal() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        createInvoice(today.minusDays(20), today.minusDays(10));

        // event এখনকার, কোনো invoice-করা period-এর ভেতরে নয়
        BatchResponse r = service.ingest(new BatchRequest(List.of(eventAt(Instant.now(), "new-1"))));

        assertEquals(1, r.accepted());
        assertEquals(0, r.late());
    }
}