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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BatchBillingTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    static final int N = 10;

    @Autowired BatchBillingService batch;
    @Autowired InvoiceService invoices;
    @Autowired SubscriptionService subscriptions;
    @Autowired IngestionService ingestion;
    @Autowired MetricService metrics;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    String prefix;
    String eventName;
    String metricCode;
    String planCode;
    final List<String> customers = new ArrayList<>();
    final List<Long> subIds = new ArrayList<>();
    final List<String> extraPlans = new ArrayList<>();
    final List<String> extraMetrics = new ArrayList<>();

    @BeforeEach
    void setUp() {
        prefix = "bat" + UUID.randomUUID().toString().substring(0, 8) + "x";
        eventName = "ev_" + UUID.randomUUID();
        metricCode = "calls_" + UUID.randomUUID();
        planCode = "plan_" + UUID.randomUUID();
        metrics.create(new MetricDefinition(null, metricCode, "Calls", "SUM", null, eventName, null));
        plans.create(planSpec(planCode, metricCode));

        // i নম্বর customer-এর usage ১০ x i, তাই বিল ১০০ + ০.৫ x ১০ x i = ১০০ + ৫i
        List<EventRequest> events = new ArrayList<>();
        for (int i = 1; i <= N; i++) {
            String c = prefix + i;
            customers.add(c);
            jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", c);
            subIds.add(subscriptions.start(
                    new SubscriptionService.StartRequest(c, planCode, null, NOV_1, 0, true)).id());
            events.add(new EventRequest(c, eventName, BigDecimal.valueOf(10L * i),
                    Instant.parse("2026-11-10T10:00:00Z"), "e" + i));
        }
        assertEquals(N, ingestion.ingest(new BatchRequest(events)).accepted());
    }

    @AfterEach
    void cleanUp() {
        String like = prefix + "%";
        String mine = "(SELECT id FROM customer WHERE external_id LIKE ?)";
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id IN " + mine + ")", like);
        jdbc.update("DELETE FROM credit_ledger WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM invoice WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM usage_event WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM subscription WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM customer WHERE external_id LIKE ?", like);
        List<String> allPlans = new ArrayList<>(extraPlans);
        allPlans.add(planCode);
        for (String code : allPlans) {
            jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", code);
            jdbc.update("DELETE FROM plan WHERE code = ?", code);
        }
        List<String> allMetrics = new ArrayList<>(extraMetrics);
        allMetrics.add(metricCode);
        for (String code : allMetrics) {
            jdbc.update("DELETE FROM billable_metric WHERE code = ?", code);
        }
    }

    private PlanService.PlanSpec planSpec(String code, String metric) {
        return new PlanService.PlanSpec(code, "Test", null, "BDT", List.of(
                new PlanService.LineSpec("Platform fee", null, Map.of("type", "FLAT", "fee", "100")),
                new PlanService.LineSpec("API calls", metric, Map.of("type", "PER_UNIT", "unitPrice", "0.5"))));
    }

    private static final String MINE = "(SELECT id FROM customer WHERE external_id LIKE ?)";

    private long invoiceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM invoice WHERE customer_id IN " + MINE,
                Long.class, prefix + "%");
    }

    private long unbilledEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE billed_invoice_id IS NULL AND customer_id IN " + MINE,
                Long.class, prefix + "%");
    }

    private long eventsOnInvoiceLines() {
        return jdbc.queryForObject(
                "SELECT CAST(COALESCE(SUM(l.event_count), 0) AS bigint) FROM invoice_line l "
                        + "JOIN invoice i ON i.id = l.invoice_id WHERE i.customer_id IN " + MINE,
                Long.class, prefix + "%");
    }

    private long invoiceIdOf(String customer) {
        return jdbc.queryForObject(
                "SELECT id FROM invoice WHERE customer_id = (SELECT id FROM customer WHERE external_id = ?)",
                Long.class, customer);
    }

    @Test
    void partitionedJobBillsEverySubscriptionExactlyOnce() {
        BatchBillingService.Result r = batch.run(DEC_1, null, prefix);

        assertEquals("COMPLETED", r.status());
        assertEquals(BillingBatchConfig.GRID_SIZE, r.partitions());
        assertEquals(N, r.subscriptionsHandled());
        assertEquals(0, r.subscriptionsSkipped());
        assertEquals(N, invoiceCount());
        assertEquals(0, unbilledEvents());
        assertEquals(N, eventsOnInvoiceLines());
        for (int i = 1; i <= N; i++) {
            Invoice inv = invoices.get(invoiceIdOf(customers.get(i - 1)));
            assertEquals("FINALISED", inv.status());
            assertEquals(0, BigDecimal.valueOf(100 + 5L * i).compareTo(inv.total()), "customer " + i);
        }
    }

    @Test
    void runningAgainCreatesNothing() {
        batch.run(DEC_1, null, prefix);

        BatchBillingService.Result again = batch.run(DEC_1, null, prefix);

        assertEquals("COMPLETED", again.status());
        assertEquals(0, again.subscriptionsHandled());      // বিল-যোগ্য আর কিছুই নেই, reader কিছু পড়েনি
        assertEquals(N, invoiceCount());                    // কোনো duplicate নেই
        assertEquals(N, eventsOnInvoiceLines());
    }

    @Test
    void oneFailedSubscriptionDoesNotStopTheOthersAndALaterRunFinishesIt() {
        String ghost = "ghost_" + UUID.randomUUID();
        String brokenPlan = "plan_" + UUID.randomUUID();
        extraPlans.add(brokenPlan);
        extraMetrics.add(ghost);
        plans.create(planSpec(brokenPlan, ghost));            // এই metric-এর সংজ্ঞা এখনও নেই

        String victim = customers.get(0);
        subscriptions.cancel(subIds.get(0));
        long brokenSub = subscriptions.start(
                new SubscriptionService.StartRequest(victim, brokenPlan, null, NOV_1, 0, true)).id();

        batch.run(DEC_1, null, prefix);

        assertEquals(N - 1, invoiceCount());                  // বাকি সবার invoice হয়েছে
        assertEquals(NOV_1, subscriptions.get(brokenSub).periodStart());   // ব্যর্থেরটার period এগোয়নি
        long victimUnbilled = jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE billed_invoice_id IS NULL "
                        + "AND customer_id = (SELECT id FROM customer WHERE external_id = ?)", Long.class, victim);
        assertEquals(1, victimUnbilled);                      // তার event ফিরে গেছে, হারায়নি

        metrics.create(new MetricDefinition(null, ghost, "Ghost", "SUM", null, eventName, null));   // ঠিক করে দিলাম
        batch.run(DEC_1, null, prefix);

        assertEquals(N, invoiceCount());
        assertEquals(0, unbilledEvents());
        assertEquals(N, eventsOnInvoiceLines());              // প্রতিটা event ঠিক একবার
        assertEquals(0, BigDecimal.valueOf(105).compareTo(invoices.get(invoiceIdOf(victim)).total()));
    }

    @Test
    void jobWithoutAnythingDueFinishesCleanly() {
        BatchBillingService.Result r = batch.run(NOV_1, null, prefix);   // period এখনও শেষ হয়নি

        assertEquals("COMPLETED", r.status());
        assertEquals(0, r.subscriptionsHandled());
        assertEquals(0, invoiceCount());
        assertEquals(N, jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE billed_invoice_id IS NULL AND customer_id IN " + MINE,
                Long.class, prefix + "%"));
    }

    @Test
    void batchMetadataIsStoredForRestartability() {
        long before = jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Long.class);

        batch.run(DEC_1, null, prefix);

        long after = jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Long.class);
        assertEquals(before + 1, after);
        long partitionSteps = jdbc.queryForObject(
                "SELECT count(*) FROM batch_step_execution WHERE step_name LIKE 'billingWorkerStep%' "
                        + "AND job_execution_id = (SELECT max(job_execution_id) FROM batch_job_execution)",
                Long.class);
        assertEquals(BillingBatchConfig.GRID_SIZE, partitionSteps);
    }
}