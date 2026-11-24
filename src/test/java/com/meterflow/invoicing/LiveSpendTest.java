package com.meterflow.invoicing;

import com.meterflow.catalogue.PlanService;
import com.meterflow.metering.BatchRequest;
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
class LiveSpendTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);

    @Autowired LiveSpendService spend;
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
    long subscriptionId;
    int keyCounter = 0;

    @BeforeEach
    void setUp() {
        customer = "ls_" + UUID.randomUUID();
        eventName = "ev_" + UUID.randomUUID();
        metricCode = "calls_" + UUID.randomUUID();
        planCode = "plan_" + UUID.randomUUID();
        metrics.create(new MetricDefinition(null, metricCode, "Calls", "SUM", null, eventName, null));
        // ১০০ flat + প্রতি call ০.৫
        plans.create(new PlanService.PlanSpec(planCode, "Test", null, "BDT", List.of(
                new PlanService.LineSpec("Platform fee", null, Map.of("type", "FLAT", "fee", "100")),
                new PlanService.LineSpec("API calls", metricCode,
                        Map.of("type", "PER_UNIT", "unitPrice", "0.5")))));
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", customer);
        subscriptionId = subscriptions.start(
                new SubscriptionService.StartRequest(customer, planCode, null, NOV_1, 0, true)).id();
    }

    @AfterEach
    void cleanUp() {
        String mine = "(SELECT id FROM customer WHERE external_id = ?)";
        jdbc.update("DELETE FROM budget_alert WHERE customer_id IN " + mine, customer);
        jdbc.update("DELETE FROM budget WHERE customer_id IN " + mine, customer);
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id IN " + mine + ")", customer);
        jdbc.update("DELETE FROM credit_ledger WHERE customer_id IN " + mine, customer);
        jdbc.update("DELETE FROM invoice WHERE customer_id IN " + mine, customer);
        jdbc.update("DELETE FROM usage_event WHERE customer_id IN " + mine, customer);
        jdbc.update("DELETE FROM subscription WHERE customer_id IN " + mine, customer);
        jdbc.update("DELETE FROM customer WHERE external_id = ?", customer);
        jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", planCode);
        jdbc.update("DELETE FROM plan WHERE code = ?", planCode);
        jdbc.update("DELETE FROM billable_metric WHERE code = ?", metricCode);
    }

    private void send(int quantity) {
        keyCounter++;
        ingestion.ingest(new BatchRequest(List.of(new EventRequest(customer, eventName,
                BigDecimal.valueOf(quantity), Instant.parse("2026-11-10T10:00:00Z"), "k" + keyCounter))));
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "amount was " + actual);
    }

    private List<Integer> thresholds() {
        return spend.alerts(customer).stream().map(LiveSpendService.Alert::threshold).toList();
    }

    @Test
    void estimateMatchesTheFinalInvoiceAndClaimsNothing() {
        send(100);
        send(300);

        LiveSpendService.Spend s = spend.estimateFresh(customer);

        assertAmount("300.00", s.estimatedTotal());                  // ১০০ + ৪০০ x ০.৫
        assertEquals(2, s.lines().size());
        long unbilled = jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE billed_invoice_id IS NULL AND customer_id = "
                        + "(SELECT id FROM customer WHERE external_id = ?)", Long.class, customer);
        assertEquals(2, unbilled);                                   // estimate কোনো event চিহ্নিত করেনি

        Invoice invoice = invoices.generate(subscriptionId, DEC_1).orElseThrow();
        assertAmount(s.estimatedTotal().toPlainString(), invoice.subtotal());   // QA5: estimate = invoice subtotal
    }

    @Test
    void alertsFireOncePerThreshold() {
        spend.setBudget(customer, new BigDecimal("200"));

        send(10);                                                    // ১০৫ = ৫২.৫%
        spend.estimateFresh(customer);
        assertEquals(List.of(50), thresholds());

        send(150);                                                   // ১৮০ = ৯০%
        spend.estimateFresh(customer);
        assertEquals(List.of(50, 80), thresholds());

        send(40);                                                    // ২০০ = ১০০%
        spend.estimateFresh(customer);
        assertEquals(List.of(50, 80, 100), thresholds());

        spend.estimateFresh(customer);                               // আবার হিসাব করলে নতুন alert নেই
        assertEquals(3, thresholds().size());
        assertAmount("100.0", spend.estimateFresh(customer).percentOfBudget());
    }

    @Test
    void resultIsCachedForAMinuteAndFreshBypassesIt() {
        send(10);
        LiveSpendService.Spend first = spend.estimateFresh(customer);
        assertAmount("105.00", first.estimatedTotal());

        send(100);
        assertAmount("105.00", spend.estimate(customer).estimatedTotal());        // cache থেকে
        assertAmount("155.00", spend.estimateFresh(customer).estimatedTotal());   // নতুন হিসাব
    }

    @Test
    void invalidRequestsAreRefused() {
        assertThrows(ResponseStatusException.class, () -> spend.estimate("nobody_" + UUID.randomUUID()));
        assertThrows(ResponseStatusException.class, () -> spend.setBudget(customer, BigDecimal.ZERO));
        assertThrows(ResponseStatusException.class, () -> spend.setBudget(customer, new BigDecimal("1.234")));
        assertThrows(ResponseStatusException.class, () -> spend.getBudget(customer));
    }
}