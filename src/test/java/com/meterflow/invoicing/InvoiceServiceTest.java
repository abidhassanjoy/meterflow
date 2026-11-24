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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class InvoiceServiceTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate NOV_30 = LocalDate.of(2026, 11, 30);
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    static final LocalDate JAN_1 = LocalDate.of(2027, 1, 1);

    @Autowired InvoiceService service;
    @Autowired SubscriptionService subscriptions;
    @Autowired IngestionService ingestion;
    @Autowired MetricService metrics;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    String customer;
    String eventName;
    String metricCode;
    String planCode;
    long customerId;
    long subscriptionId;

    @BeforeEach
    void setUp() {
        customer = "inv_" + UUID.randomUUID();
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

        // নভেম্বরের period-এর বাইরে দুটো (অক্টোবর আর ডিসেম্বর), ভেতরে দুটো (মোট ৩০০০)
        BatchResponse r = ingestion.ingest(new BatchRequest(List.of(
                ev("oct", "999", "2026-10-31T23:00:00Z"),
                ev("n1", "1500", "2026-11-05T10:00:00Z"),
                ev("n2", "1500", "2026-11-20T10:00:00Z"),
                ev("dec", "777", "2026-12-01T10:00:00Z"))));
        assertEquals(4, r.accepted());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM invoice WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM usage_event WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM subscription WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", planCode);
        jdbc.update("DELETE FROM plan WHERE code = ?", planCode);
        jdbc.update("DELETE FROM billable_metric WHERE code = ?", metricCode);
    }

    private EventRequest ev(String key, String qty, String at) {
        return new EventRequest(customer, eventName, new BigDecimal(qty), Instant.parse(at), key);
    }

    private long billedTo(long invoiceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE customer_id = ? AND billed_invoice_id = ?",
                Long.class, customerId, invoiceId);
    }

    private long unbilled() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE customer_id = ? AND billed_invoice_id IS NULL",
                Long.class, customerId);
    }

    private long invoiceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM invoice WHERE customer_id = ?", Long.class, customerId);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "amount was " + actual);
    }

    @Test
    void createsFinalisedInvoiceWithExplainedLines() {
        Invoice inv = service.generate(subscriptionId, DEC_1).orElseThrow();

        assertEquals("FINALISED", inv.status());
        assertEquals(customer, inv.customer());
        assertEquals(NOV_1, inv.periodStart());
        assertEquals(NOV_30, inv.periodEnd());
        assertEquals("BDT", inv.currency());
        assertEquals(2, inv.lines().size());

        InvoiceLine flat = inv.lines().get(0);
        assertAmount("500.00", flat.amount());
        assertEquals(0, flat.eventCount());
        assertNull(flat.metricCode());

        InvoiceLine calls = inv.lines().get(1);
        assertEquals(metricCode, calls.metricCode());
        assertAmount("3000", calls.quantity());                 // ১৫০০ + ১৫০০
        assertEquals(2, calls.eventCount());
        assertAmount("100.00", calls.amount());                 // (৩০০০ - ১০০০) x ০.০৫
        assertEquals("1000 free units, then 2000 billable: 2000 units x 0.05", calls.priceRule());

        assertAmount("600.00", inv.subtotal());
        assertAmount("0", inv.tax());
        assertAmount("600.00", inv.total());

        SubscriptionService.View sub = subscriptions.get(subscriptionId);   // পরের period শুরু হয়েছে
        assertEquals("ACTIVE", sub.status());
        assertEquals(DEC_1, sub.periodStart());
        assertEquals(LocalDate.of(2026, 12, 31), sub.periodEnd());
    }

    @Test
    void eventsAreClaimedOnlyForThePeriod() {
        Invoice inv = service.generate(subscriptionId, DEC_1).orElseThrow();

        assertEquals(2, billedTo(inv.id()));      // শুধু নভেম্বরের দুটো
        assertEquals(2, unbilled());              // অক্টোবর আর ডিসেম্বরেরটা অক্ষত
    }

    @Test
    void secondRunBillsNothing() {
        Invoice first = service.generate(subscriptionId, DEC_1).orElseThrow();

        assertTrue(service.generate(subscriptionId, DEC_1).isEmpty());   // একই দিনে আবার: কিছু নেই
        assertEquals(1, invoiceCount());
        assertEquals(2, billedTo(first.id()));
        assertEquals(2, unbilled());
    }

    @Test
    void periodNotFinishedIsNotBilled() {
        assertTrue(service.generate(subscriptionId, NOV_30).isEmpty());   // ৩০ নভেম্বরেও period শেষ হয়নি
        assertEquals(0, invoiceCount());
        assertEquals(4, unbilled());
        assertEquals(NOV_1, subscriptions.get(subscriptionId).periodStart());
    }

    @Test
    void nextPeriodBillsOnlyNewEvents() {
        Invoice first = service.generate(subscriptionId, DEC_1).orElseThrow();

        BatchResponse r = ingestion.ingest(new BatchRequest(List.of(ev("dec2", "100", "2026-12-10T10:00:00Z"))));
        assertEquals(1, r.accepted());

        Invoice second = service.generate(subscriptionId, JAN_1).orElseThrow();

        assertNotEquals(first.id(), second.id());
        assertEquals(LocalDate.of(2026, 12, 1), second.periodStart());
        InvoiceLine calls = second.lines().get(1);
        assertAmount("877", calls.quantity());                // ৭৭৭ + ১০০, নভেম্বরের কিছুই নয়
        assertEquals(2, calls.eventCount());
        assertAmount("0.00", calls.amount());                 // ১০০০ বিনামূল্যের মধ্যে
        assertAmount("500.00", second.total());

        assertEquals(2, billedTo(first.id()));                // প্রথম invoice-এর event অপরিবর্তিত
        assertEquals(2, billedTo(second.id()));
        assertEquals(1, unbilled());                          // শুধু অক্টোবরেরটা
    }

    @Test
    void failureRollsBackEverything() {
        jdbc.update("DELETE FROM billable_metric WHERE code = ?", metricCode);   // metric-এর সংজ্ঞা নেই, তাই হিসাব ব্যর্থ

        assertThrows(IllegalStateException.class, () -> service.generate(subscriptionId, DEC_1));

        assertEquals(0, invoiceCount());                      // invoice নেই
        assertEquals(4, unbilled());                          // কোনো event চিহ্নিত হয়নি
        SubscriptionService.View sub = subscriptions.get(subscriptionId);
        assertEquals("ACTIVE", sub.status());
        assertEquals(NOV_1, sub.periodStart());               // period এগোয়নি
    }

    @Test
    void pausedSubscriptionIsNotBilled() {
        subscriptions.pause(subscriptionId);

        assertThrows(ResponseStatusException.class, () -> service.generate(subscriptionId, DEC_1));
        assertEquals(0, invoiceCount());
    }

    @Test
    void invoiceCanBeFetchedAgain() {
        Invoice inv = service.generate(subscriptionId, DEC_1).orElseThrow();

        Invoice again = service.get(inv.id());
        assertEquals(2, again.lines().size());
        assertAmount("600.00", again.total());
        assertEquals(inv.lines().get(1).priceRule(), again.lines().get(1).priceRule());

        assertThrows(ResponseStatusException.class, () -> service.get(999_999_999L));
    }
}