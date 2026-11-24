package com.meterflow.invoicing;

import com.meterflow.catalogue.PlanService;
import com.meterflow.metering.BatchRequest;
import com.meterflow.metering.BatchResponse;
import com.meterflow.metering.EventRequest;
import com.meterflow.metering.IngestionService;
import com.meterflow.metering.MetricDefinition;
import com.meterflow.metering.MetricService;
import com.meterflow.subscriptions.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class InvoiceLateUsageTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    static final LocalDate JAN_1 = LocalDate.of(2027, 1, 1);
    static final LocalDate FEB_1 = LocalDate.of(2027, 2, 1);

    @Autowired InvoiceService invoices;
    @Autowired SubscriptionService subscriptions;
    @Autowired IngestionService ingestion;
    @Autowired MetricService metrics;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    String customer;
    String eventName;
    String metricCode;
    String planCode;
    String taxCode;
    long customerId;
    long subscriptionId;

    @BeforeEach
    void setUp() {
        customer = "late_" + UUID.randomUUID();
        eventName = "ev_" + UUID.randomUUID();
        metricCode = "calls_" + UUID.randomUUID();
        planCode = "plan_" + UUID.randomUUID();

        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", customer);
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?", Long.class, customer);

        metrics.create(new MetricDefinition(null, metricCode, "Calls", "SUM", null, eventName, null));

        // ৫০০ flat + প্রথম ১০০০ call বিনামূল্যে, তারপর প্রতি call ০.০৫
        plans.create(new PlanService.PlanSpec(planCode, "Test", null, "BDT", List.of(
                new PlanService.LineSpec("Platform fee", null, Map.of("type", "FLAT", "fee", "500")),
                new PlanService.LineSpec("API calls", metricCode,
                        Map.of("type", "FREE_ALLOWANCE", "freeUnits", "1000",
                                "inner", Map.of("type", "PER_UNIT", "unitPrice", "0.05"))))));

        subscriptionId = subscriptions.start(
                new SubscriptionService.StartRequest(customer, planCode, null, NOV_1, 0, true)).id();

        // নভেম্বরে দুটো সাধারণ event, মোট ৩০০০
        BatchResponse r = ingestion.ingest(new BatchRequest(List.of(
                ev("n1", "1500", "2026-11-05T10:00:00Z"),
                ev("n2", "1500", "2026-11-20T10:00:00Z"))));
        assertEquals(2, r.accepted());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM credit_ledger WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM invoice WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM usage_event WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM subscription WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        if (taxCode != null) {
            jdbc.update("DELETE FROM tax_rule WHERE code = ?", taxCode);
        }
        jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", planCode);
        jdbc.update("DELETE FROM plan WHERE code = ?", planCode);
        jdbc.update("DELETE FROM billable_metric WHERE code = ?", metricCode);
    }

    private EventRequest ev(String key, String qty, String at) {
        return new EventRequest(customer, eventName, new BigDecimal(qty), Instant.parse(at), key);
    }

    /** ingestion যেভাবে late event সংরক্ষণ করে (late = true), সেভাবে সরাসরি বসায়, যাতে তারিখ আমাদের হাতে থাকে। */
    private void lateEvent(String key, String qty, String at) {
        jdbc.update("INSERT INTO usage_event (customer_id, metric, quantity, occurred_at, idempotency_key, late) "
                        + "VALUES (?, ?, ?, ?, ?, TRUE)",
                customerId, eventName, new BigDecimal(qty), Timestamp.from(Instant.parse(at)), key);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "amount was " + actual);
    }

    private long unbilledLate() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE customer_id = ? AND late = TRUE AND billed_invoice_id IS NULL",
                Long.class, customerId);
    }

    @Test
    void lateEventsBecomeAnAdjustmentOnTheNextInvoice() {
        Invoice first = invoices.generate(subscriptionId, DEC_1).orElseThrow();
        assertAmount("600.00", first.total());                      // ৫০০ + (৩০০০ - ১০০০) x ০.০৫

        lateEvent("l1", "500", "2026-11-25T10:00:00Z");
        lateEvent("l2", "250", "2026-11-28T10:00:00Z");

        Invoice second = invoices.generate(subscriptionId, JAN_1).orElseThrow();

        assertEquals(3, second.lines().size());
        InvoiceLine calls = second.lines().get(1);                   // ডিসেম্বরের নিজের usage: কিছুই নেই
        assertAmount("0", calls.quantity());
        assertEquals(0, calls.eventCount());
        assertAmount("0.00", calls.amount());

        InvoiceLine late = second.lines().get(2);
        assertEquals("LATE_USAGE", late.lineType());
        assertEquals(metricCode, late.metricCode());
        assertAmount("750", late.quantity());                        // ৫০০ + ২৫০
        assertEquals(2, late.eventCount());
        assertAmount("37.50", late.amount());                        // (৩৭৫০ - ১০০০) x ০.০৫ = ১৩৭.৫০, আগে ১০০.০০
        assertEquals("re-priced with late events: 100.00 -> 137.50", late.priceRule());
        assertAmount("537.50", second.total());                      // ৫০০ + ০ + ৩৭.৫০
        assertEquals(0, unbilledLate());
    }

    @Test
    void lateEventsAreBilledOnlyOnce() {
        invoices.generate(subscriptionId, DEC_1).orElseThrow();
        lateEvent("l1", "500", "2026-11-25T10:00:00Z");
        lateEvent("l2", "250", "2026-11-28T10:00:00Z");
        Invoice second = invoices.generate(subscriptionId, JAN_1).orElseThrow();

        Invoice third = invoices.generate(subscriptionId, FEB_1).orElseThrow();

        assertEquals(2, third.lines().size());                       // আর কোনো LATE_USAGE নেই
        assertAmount("500.00", third.total());
        long billedToSecond = jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE customer_id = ? AND billed_invoice_id = ? AND late = TRUE",
                Long.class, customerId, second.id());
        assertEquals(2, billedToSecond);
    }

    @Test
    void lateAdjustmentCarriesTax() {
        taxCode = "vat_" + UUID.randomUUID();
        jdbc.update("INSERT INTO tax_rule (code, name, rate) VALUES (?, 'VAT', 0.15)", taxCode);
        jdbc.update("UPDATE customer SET tax_rule_code = ? WHERE id = ?", taxCode, customerId);

        Invoice first = invoices.generate(subscriptionId, DEC_1).orElseThrow();
        assertAmount("690.00", first.total());                       // ৬০০ + ৯০ কর

        lateEvent("l1", "500", "2026-11-25T10:00:00Z");
        lateEvent("l2", "250", "2026-11-28T10:00:00Z");
        Invoice second = invoices.generate(subscriptionId, JAN_1).orElseThrow();

        InvoiceLine late = second.lines().get(2);
        assertAmount("37.50", late.amount());
        assertAmount("5.63", late.taxAmount());                      // ৫.৬২৫ উপরে
        assertAmount("537.50", second.subtotal());
        assertAmount("80.63", second.tax());                         // ৭৫.০০ + ০.০০ + ৫.৬৩
        assertAmount("618.13", second.total());
    }

    @Test
    void laterLateEventsOnlyAddTheDifference() {
        invoices.generate(subscriptionId, DEC_1).orElseThrow();      // নভেম্বর: ৩০০০ ইউনিট, ১০০.০০

        lateEvent("l1", "500", "2026-11-25T10:00:00Z");
        Invoice second = invoices.generate(subscriptionId, JAN_1).orElseThrow();
        InvoiceLine firstLate = second.lines().get(2);
        assertAmount("25.00", firstLate.amount());                   // ১০০.০০ থেকে ১২৫.০০
        assertAmount("500", firstLate.quantity());
        assertEquals(1, firstLate.eventCount());
        assertAmount("525.00", second.total());

        lateEvent("l2", "250", "2026-11-28T10:00:00Z");              // দ্বিতীয় invoice-এর পরে আরেকটা
        Invoice third = invoices.generate(subscriptionId, FEB_1).orElseThrow();

        InvoiceLine secondLate = third.lines().get(2);
        assertEquals("LATE_USAGE", secondLate.lineType());
        assertAmount("12.50", secondLate.amount());                  // ১২৫.০০ থেকে ১৩৭.৫০, আবার ২৫ নয়
        assertAmount("250", secondLate.quantity());
        assertEquals(1, secondLate.eventCount());
        assertEquals("re-priced with late events: 125.00 -> 137.50", secondLate.priceRule());
        assertAmount("512.50", third.total());

        // মোট সমন্বয় = ২৫.০০ + ১২.৫০ = ৩৭.৫০ = ১৩৭.৫০ - ১০০.০০, কিছু দুবার বিল হয়নি
    }
}