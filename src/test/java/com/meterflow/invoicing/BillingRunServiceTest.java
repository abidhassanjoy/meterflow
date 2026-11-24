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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BillingRunServiceTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate NOV_30 = LocalDate.of(2026, 11, 30);
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    static final LocalDate FEB_1 = LocalDate.of(2027, 2, 1);
    static final int N = 12;
    static final int ALL = Integer.MAX_VALUE;

    @Autowired BillingRunService billing;
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
        prefix = "run" + UUID.randomUUID().toString().substring(0, 8) + "x";
        eventName = "ev_" + UUID.randomUUID();
        metricCode = "calls_" + UUID.randomUUID();
        planCode = "plan_" + UUID.randomUUID();
        metrics.create(new MetricDefinition(null, metricCode, "Calls", "SUM", null, eventName, null));
        plans.create(planSpec(planCode, metricCode));

        // N জন customer। i নম্বর customer-এর usage ১০ x i, তাই বিল ১০০ + ০.৫ x ১০ x i = ১০০ + ৫i
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

    /** সব invoice line-এর event সংখ্যার যোগফল। প্রতিটা event ঠিক একবার গোনা হলে এটা event সংখ্যার সমান। */
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

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "amount was " + actual);
    }

    @Test
    void billsEveryDueSubscriptionExactlyOnce() {
        BillingRunService.Result r = billing.runFor(subIds, DEC_1, 4, ALL);

        assertEquals(N, r.subscriptionsSeen());
        assertEquals(N, r.invoicesCreated());
        assertEquals(0, r.failed());
        assertEquals(N, invoiceCount());
        assertEquals(0, unbilledEvents());
        assertEquals(N, eventsOnInvoiceLines());
        for (int i = 1; i <= N; i++) {
            Invoice inv = invoices.get(invoiceIdOf(customers.get(i - 1)));
            assertEquals("FINALISED", inv.status());
            assertAmount(BigDecimal.valueOf(100 + 5L * i), inv.total());
        }
    }

    @Test
    void secondRunCreatesNothing() {
        billing.runFor(subIds, DEC_1, 4, ALL);

        BillingRunService.Result again = billing.runFor(subIds, DEC_1, 4, ALL);

        assertEquals(0, again.invoicesCreated());
        assertEquals(0, again.failed());
        assertEquals(N, invoiceCount());
        assertEquals(N, eventsOnInvoiceLines());
    }

    @Test
    void restartAfterAPartialRunCompletesTheRest() {
        BillingRunService.Result first = billing.runFor(subIds, DEC_1, 4, 5);    // মাঝপথে বন্ধ হয়ে গেলো
        assertEquals(5, first.invoicesCreated());
        assertEquals(5, invoiceCount());

        BillingRunService.Result second = billing.runFor(subIds, DEC_1, 4, ALL); // আবার চালু

        assertEquals(N - 5, second.invoicesCreated());
        assertEquals(0, second.failed());
        assertEquals(N, invoiceCount());                    // কোনো duplicate নেই
        assertEquals(0, unbilledEvents());
        assertEquals(N, eventsOnInvoiceLines());            // প্রতিটা event ঠিক একবার
    }

    @Test
    void oneFailureDoesNotStopTheOthersAndCanBeRetried() {
        String ghost = "ghost_" + UUID.randomUUID();
        String brokenPlan = "plan_" + UUID.randomUUID();
        extraPlans.add(brokenPlan);
        extraMetrics.add(ghost);
        plans.create(planSpec(brokenPlan, ghost));            // এই metric-এর সংজ্ঞা এখনও নেই

        String victim = customers.get(0);
        subscriptions.cancel(subIds.get(0));
        long brokenSub = subscriptions.start(
                new SubscriptionService.StartRequest(victim, brokenPlan, null, NOV_1, 0, true)).id();
        List<Long> ids = new ArrayList<>(subIds.subList(1, N));
        ids.add(brokenSub);

        BillingRunService.Result r = billing.runFor(ids, DEC_1, 4, ALL);

        assertEquals(N - 1, r.invoicesCreated());
        assertEquals(1, r.failed());
        assertEquals(brokenSub, r.failures().get(0).subscriptionId());
        assertTrue(r.failures().get(0).reason().contains(ghost));
        assertEquals(N - 1, invoiceCount());
        assertEquals(NOV_1, subscriptions.get(brokenSub).periodStart());    // period এগোয়নি
        long victimUnbilled = jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE billed_invoice_id IS NULL "
                        + "AND customer_id = (SELECT id FROM customer WHERE external_id = ?)", Long.class, victim);
        assertEquals(1, victimUnbilled);                                    // চিহ্নিত event ফিরে গেছে

        metrics.create(new MetricDefinition(null, ghost, "Ghost", "SUM", null, eventName, null));   // ঠিক করে দিলাম
        BillingRunService.Result retry = billing.runFor(ids, DEC_1, 4, ALL);

        assertEquals(1, retry.invoicesCreated());
        assertEquals(0, retry.failed());
        assertEquals(N, invoiceCount());
        assertAmount(BigDecimal.valueOf(105), invoices.get(invoiceIdOf(victim)).total());   // ১০০ + ৫
        assertEquals(0, unbilledEvents());
        assertEquals(N, eventsOnInvoiceLines());
    }

    @Test
    void twoRunsAtTheSameTimeStillBillEachPeriodOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BillingRunService.Result>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return billing.runFor(subIds, DEC_1, 3, ALL);
            }));
        }
        start.countDown();
        int created = 0;
        int failed = 0;
        for (Future<BillingRunService.Result> f : futures) {
            BillingRunService.Result r = f.get();
            created += r.invoicesCreated();
            failed += r.failed();
        }
        pool.shutdown();

        assertEquals(N, created);                           // দুটো মিলে ঠিক N, ২N নয়
        assertEquals(0, failed);
        assertEquals(N, invoiceCount());
        assertEquals(0, unbilledEvents());
        assertEquals(N, eventsOnInvoiceLines());
    }

    @Test
    void catchesUpSeveralMissedPeriodsInOneRun() {
        String c = prefix + "extra";
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", c);
        long sub = subscriptions.start(new SubscriptionService.StartRequest(c, planCode, null, NOV_1, 0, true)).id();

        BillingRunService.Result r = billing.runFor(List.of(sub), FEB_1, 2, ALL);

        assertEquals(3, r.invoicesCreated());               // নভেম্বর, ডিসেম্বর, জানুয়ারি
        assertEquals(0, r.failed());
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id = (SELECT id FROM customer WHERE external_id = ?)",
                Long.class, c);
        assertEquals(3, count);
        assertEquals(FEB_1, subscriptions.get(sub).periodStart());
    }

    @Test
    void dueSelectionFindsOnlyFinishedPeriods() {
        assertTrue(subscriptions.dueForBilling(DEC_1).containsAll(subIds));
        List<Long> notYet = subscriptions.dueForBilling(NOV_30);   // ৩০ নভেম্বরেও period শেষ হয়নি
        assertTrue(subIds.stream().noneMatch(notYet::contains));
    }

    @Test
    void invalidSettingsAreRefused() {
        assertThrows(ResponseStatusException.class, () -> billing.runFor(subIds, DEC_1, 0, ALL));
        assertThrows(ResponseStatusException.class, () -> billing.runFor(subIds, DEC_1, 9, ALL));
        assertThrows(ResponseStatusException.class, () -> billing.runFor(subIds, DEC_1, 4, 0));
        assertThrows(ResponseStatusException.class, () -> billing.runFor(subIds, null, 4, ALL));
        assertEquals(0, invoiceCount());
    }
}