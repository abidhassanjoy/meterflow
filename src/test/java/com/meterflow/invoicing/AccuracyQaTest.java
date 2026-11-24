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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA1: ১০ লক্ষ event, ৩% duplicate, ২% late। invoice গুলো স্বাধীন হিসাবের সাথে পয়সা পর্যন্ত মিলতে হবে।
 * অনেক সময় লাগে (কয়েক মিনিট), তাই সাধারণ test-এ চলে না। শুধু QA_RUN=true থাকলে চলে।
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "QA_RUN", matches = "true")
class AccuracyQaTest {

    static final int CUSTOMERS = 1000;
    static final int CHUNKS = 1000;
    static final int UNIQUE_PER_CHUNK = 950;
    static final int DUPLICATES_PER_CHUNK = 25;
    static final int CROSS_DUPLICATES = 5000;
    static final int LATE_EVENTS = 20_000;
    static final int ON_TIME_UNIQUE = CHUNKS * UNIQUE_PER_CHUNK;                // 950,000
    static final int WITHIN_BATCH_DUPLICATES = CHUNKS * DUPLICATES_PER_CHUNK;   // 25,000

    static final LocalDate JAN_1 = LocalDate.of(2030, 1, 1);
    static final LocalDate FEB_1 = LocalDate.of(2030, 2, 1);
    static final LocalDate MAR_1 = LocalDate.of(2030, 3, 1);
    static final Instant JAN_START = Instant.parse("2030-01-01T00:00:00Z");
    static final int JAN_SECONDS = 31 * 24 * 3600;
    static final BigDecimal VAT = new BigDecimal("0.15");
    static final BigDecimal FLAT_FEE = new BigDecimal("100");

    @Autowired IngestionService ingestion;
    @Autowired BillingRunService billing;
    @Autowired SubscriptionService subscriptions;
    @Autowired MetricService metrics;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    String prefix;
    String like;
    String eventName;
    String metricCode;
    String planCode;
    String taxCode;

    record Row(String customer, LocalDate periodStart, String lineType, String metric, BigDecimal quantity,
               long eventCount, BigDecimal amount, BigDecimal tax, BigDecimal invoiceTotal) {
    }

    @AfterEach
    void cleanUp() {
        if (prefix == null) {
            return;
        }
        String mine = "(SELECT id FROM customer WHERE external_id LIKE ?)";
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id IN " + mine + ")", like);
        jdbc.update("DELETE FROM credit_ledger WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM invoice WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM usage_event WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM subscription WHERE customer_id IN " + mine, like);
        jdbc.update("DELETE FROM customer WHERE external_id LIKE ?", like);
        jdbc.update("DELETE FROM tax_rule WHERE code = ?", taxCode);
        jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", planCode);
        jdbc.update("DELETE FROM plan WHERE code = ?", planCode);
        jdbc.update("DELETE FROM billable_metric WHERE code = ?", metricCode);
    }

    // ---------- স্বাধীন হিসাব ----------

    /** plan-এর দাম আলাদা সূত্রে: ১০০ ইউনিট বিনামূল্যে, তারপর ৫০০ পর্যন্ত ০.১০, ২০০০ পর্যন্ত ০.০৮, বাকি ০.০৫। */
    static BigDecimal price(BigDecimal quantity) {
        BigDecimal billable = quantity.subtract(new BigDecimal("100")).max(BigDecimal.ZERO);
        BigDecimal first = billable.min(new BigDecimal("500")).multiply(new BigDecimal("0.10"));
        BigDecimal second = billable.min(new BigDecimal("2000")).subtract(new BigDecimal("500"))
                .max(BigDecimal.ZERO).multiply(new BigDecimal("0.08"));
        BigDecimal third = billable.subtract(new BigDecimal("2000")).max(BigDecimal.ZERO)
                .multiply(new BigDecimal("0.05"));
        return first.add(second).add(third).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal vat(BigDecimal amount) {
        return amount.multiply(VAT).setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- সহায়ক ----------

    private static BigDecimal quantity(Random rnd) {
        return BigDecimal.valueOf(rnd.nextInt(80) + 1).divide(BigDecimal.valueOf(4));
    }

    private EventRequest event(int customer, BigDecimal qty, int second, String key) {
        return new EventRequest(prefix + customer, eventName, qty, JAN_START.plusSeconds(second), key);
    }

    private Future<BatchResponse> submit(ExecutorService pool, Semaphore inFlight, List<EventRequest> batch)
            throws InterruptedException {
        inFlight.acquire();
        return pool.submit(() -> {
            try {
                return ingestion.ingest(new BatchRequest(batch));
            } finally {
                inFlight.release();
            }
        });
    }

    /** [accepted, duplicates, rejected, late] */
    private static long[] total(List<Future<BatchResponse>> futures) throws Exception {
        long[] t = new long[4];
        for (Future<BatchResponse> f : futures) {
            BatchResponse r = f.get();
            t[0] += r.accepted();
            t[1] += r.duplicates();
            t[2] += r.rejected();
            t[3] += r.late();
        }
        return t;
    }

    private static void sameAmount(List<String> problems, String what, BigDecimal expected, BigDecimal actual) {
        if (actual == null || expected.compareTo(actual) != 0) {
            problems.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void sameCount(List<String> problems, String what, long expected, Long actual) {
        if (actual == null || expected != actual) {
            problems.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static Row pick(List<Row> rows, LocalDate period, String type, String metric) {
        for (Row r : rows) {
            if (r.periodStart().equals(period) && r.lineType().equals(type) && Objects.equals(r.metric(), metric)) {
                return r;
            }
        }
        return null;
    }

    private static BigDecimal invoiceTotal(List<Row> rows, LocalDate period) {
        for (Row r : rows) {
            if (r.periodStart().equals(period)) {
                return r.invoiceTotal();
            }
        }
        return null;
    }

    // ---------- test ----------

    @Test
    void oneMillionEventsBillExactlyAndMatchAnIndependentRecomputation() throws Exception {
        prefix = "qa" + UUID.randomUUID().toString().substring(0, 8) + "x";
        like = prefix + "%";
        eventName = "qa_ev_" + UUID.randomUUID();
        metricCode = "qa_calls_" + UUID.randomUUID();
        planCode = "qa_plan_" + UUID.randomUUID();
        taxCode = "qa_vat_" + UUID.randomUUID();

        Random rnd = new Random(20261119L);
        BigDecimal[] onQty = new BigDecimal[CUSTOMERS];
        BigDecimal[] lateQty = new BigDecimal[CUSTOMERS];
        Arrays.fill(onQty, BigDecimal.ZERO);
        Arrays.fill(lateQty, BigDecimal.ZERO);
        long[] onCount = new long[CUSTOMERS];
        long[] lateCount = new long[CUSTOMERS];

        // ---- fixtures: কর, metric, plan, ১০০০ customer ও subscription ----
        jdbc.update("INSERT INTO tax_rule (code, name, rate) VALUES (?, 'VAT', 0.15)", taxCode);
        metrics.create(new MetricDefinition(null, metricCode, "QA calls", "SUM", null, eventName, null));
        plans.create(new PlanService.PlanSpec(planCode, "QA plan", null, "BDT", List.of(
                new PlanService.LineSpec("Platform fee", null, Map.of("type", "FLAT", "fee", "100")),
                new PlanService.LineSpec("API calls", metricCode,
                        Map.of("type", "FREE_ALLOWANCE", "freeUnits", "100",
                                "inner", Map.of("type", "GRADUATED", "tiers", List.of(
                                        Map.of("upTo", "500", "unitPrice", "0.10"),
                                        Map.of("upTo", "2000", "unitPrice", "0.08"),
                                        Map.of("unitPrice", "0.05"))))))));
        List<Long> subIds = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++) {
            jdbc.update("INSERT INTO customer (external_id, name, currency, tax_rule_code) VALUES (?, 'QA', 'BDT', ?)",
                    prefix + i, taxCode);
            subIds.add(subscriptions.start(
                    new SubscriptionService.StartRequest(prefix + i, planCode, null, JAN_1, 0, true)).id());
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Semaphore inFlight = new Semaphore(16);
        long ingestMillis;
        BillingRunService.Result run1;
        BillingRunService.Result run2;
        long crossDuplicates = 0;
        long[] p1;
        long[] p3;
        try {
            // ---- ধাপ ১: সময়মতো আসা event, প্রতি batch-এ ২৫টা নিজের ভেতরের duplicate ----
            long t0 = System.nanoTime();
            List<Future<BatchResponse>> futures = new ArrayList<>();
            List<EventRequest> sample = new ArrayList<>();
            long keySeq = 0;
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                List<EventRequest> batch = new ArrayList<>(UNIQUE_PER_CHUNK + DUPLICATES_PER_CHUNK);
                for (int j = 0; j < UNIQUE_PER_CHUNK; j++) {
                    int c = rnd.nextInt(CUSTOMERS);
                    BigDecimal qty = quantity(rnd);
                    EventRequest e = event(c, qty, rnd.nextInt(JAN_SECONDS), "e" + keySeq++);
                    batch.add(e);
                    onQty[c] = onQty[c].add(qty);
                    onCount[c]++;
                    if (j % 190 == 0) {
                        sample.add(e);        // প্রতি chunk-এ ৫টা, পরে আবার পাঠানো হবে
                    }
                }
                for (int d = 0; d < DUPLICATES_PER_CHUNK; d++) {
                    batch.add(batch.get(rnd.nextInt(UNIQUE_PER_CHUNK)));
                }
                Collections.shuffle(batch, rnd);
                futures.add(submit(pool, inFlight, batch));
            }
            p1 = total(futures);
            assertEquals(ON_TIME_UNIQUE, p1[0], "accepted on-time events");
            assertEquals(WITHIN_BATCH_DUPLICATES, p1[1], "duplicates inside batches");
            assertEquals(0, p1[2], "rejected");
            assertEquals(0, p1[3], "nothing is late before the first invoice");

            // ---- ধাপ ২: আগে পাঠানো event আবার (batch পেরিয়ে duplicate) ----
            Collections.shuffle(sample, rnd);
            for (int i = 0; i < sample.size(); i += 1000) {
                List<EventRequest> again = new ArrayList<>(sample.subList(i, Math.min(i + 1000, sample.size())));
                BatchResponse r = ingestion.ingest(new BatchRequest(again));
                assertEquals(0, r.accepted(), "a resent event must never be stored again");
                crossDuplicates += r.duplicates();
            }
            assertEquals(CROSS_DUPLICATES, crossDuplicates);

            // ---- ধাপ ৩: জানুয়ারির billing run ----
            long t1 = System.nanoTime();
            run1 = billing.runFor(subIds, FEB_1, 4, Integer.MAX_VALUE);
            assertEquals(CUSTOMERS, run1.invoicesCreated(),
                    "failures: " + run1.failures().stream().limit(5).toList());
            assertEquals(0, run1.failed());

            // ---- ধাপ ৪: invoice হয়ে যাওয়ার পর আসা late event ----
            List<Future<BatchResponse>> lateFutures = new ArrayList<>();
            List<EventRequest> lateBatch = new ArrayList<>();
            for (int i = 0; i < LATE_EVENTS; i++) {
                int c = rnd.nextInt(CUSTOMERS);
                BigDecimal qty = quantity(rnd);
                lateBatch.add(event(c, qty, rnd.nextInt(JAN_SECONDS), "l" + i));
                lateQty[c] = lateQty[c].add(qty);
                lateCount[c]++;
                if (lateBatch.size() == 1000) {
                    lateFutures.add(submit(pool, inFlight, lateBatch));
                    lateBatch = new ArrayList<>();
                }
            }
            p3 = total(lateFutures);
            assertEquals(LATE_EVENTS, p3[0], "accepted late events");
            assertEquals(LATE_EVENTS, p3[3], "events flagged late");
            assertEquals(0, p3[2], "rejected late events");
            ingestMillis = (t1 - t0) / 1_000_000;

            // ---- ধাপ ৫: ফেব্রুয়ারির billing run (late event-এর সমন্বয় এখানে ওঠে) ----
            run2 = billing.runFor(subIds, MAR_1, 4, Integer.MAX_VALUE);
            assertEquals(CUSTOMERS, run2.invoicesCreated(),
                    "failures: " + run2.failures().stream().limit(5).toList());
            assertEquals(0, run2.failed());
        } finally {
            pool.shutdown();
        }

        // ---- যাচাই ১: কোনো event দুবার জমা বা বিল হয়নি ----
        long stored = jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE customer_id IN (SELECT id FROM customer WHERE external_id LIKE ?)",
                Long.class, like);
        assertEquals(ON_TIME_UNIQUE + LATE_EVENTS, stored, "stored events (duplicates must be gone)");
        long unbilled = jdbc.queryForObject(
                "SELECT count(*) FROM usage_event WHERE billed_invoice_id IS NULL "
                        + "AND customer_id IN (SELECT id FROM customer WHERE external_id LIKE ?)", Long.class, like);
        assertEquals(0, unbilled, "every event must be billed");
        List<String> groups = jdbc.query(
                "SELECT i.period_start, e.late, count(*) FROM usage_event e JOIN invoice i ON i.id = e.billed_invoice_id "
                        + "WHERE e.customer_id IN (SELECT id FROM customer WHERE external_id LIKE ?) "
                        + "GROUP BY i.period_start, e.late ORDER BY 1, 2",
                (rs, n) -> rs.getDate(1).toLocalDate() + "/" + rs.getBoolean(2) + "=" + rs.getLong(3), like);
        assertEquals(List.of("2030-01-01/false=950000", "2030-02-01/true=20000"), groups);
        long invoiceCount = jdbc.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id IN (SELECT id FROM customer WHERE external_id LIKE ?)",
                Long.class, like);
        assertEquals(2L * CUSTOMERS, invoiceCount);
        long dueMismatch = jdbc.queryForObject(
                "SELECT count(*) FROM invoice WHERE amount_due <> total "
                        + "AND customer_id IN (SELECT id FROM customer WHERE external_id LIKE ?)", Long.class, like);
        assertEquals(0, dueMismatch);

        // ---- যাচাই ২: প্রতিটা customer-এর জমা event আর invoice, স্বাধীন হিসাবের সাথে ----
        Map<String, BigDecimal> storedQty = new HashMap<>();
        Map<String, Long> storedCount = new HashMap<>();
        jdbc.query("SELECT c.external_id, SUM(e.quantity), count(*) FROM usage_event e "
                        + "JOIN customer c ON c.id = e.customer_id WHERE c.external_id LIKE ? GROUP BY c.external_id",
                rs -> {
                    storedQty.put(rs.getString(1), rs.getBigDecimal(2));
                    storedCount.put(rs.getString(1), rs.getLong(3));
                }, like);

        List<Row> rows = jdbc.query(
                "SELECT c.external_id, i.period_start, l.line_type, l.metric_code, l.quantity, l.event_count, "
                        + "l.amount, l.tax_amount, i.total FROM invoice i JOIN customer c ON c.id = i.customer_id "
                        + "JOIN invoice_line l ON l.invoice_id = i.id WHERE c.external_id LIKE ?",
                (rs, n) -> new Row(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getLong(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9)),
                like);
        Map<String, List<Row>> byCustomer = rows.stream().collect(Collectors.groupingBy(Row::customer));

        List<String> problems = new ArrayList<>();
        BigDecimal expectedGrand = BigDecimal.ZERO;
        for (int c = 0; c < CUSTOMERS; c++) {
            String name = prefix + c;
            List<Row> mine = byCustomer.getOrDefault(name, List.of());
            BigDecimal everything = onQty[c].add(lateQty[c]);
            BigDecimal priceOn = price(onQty[c]);
            BigDecimal delta = price(everything).subtract(priceOn);   // late সমন্বয় = পুনর্হিসাব - আগের দাম

            BigDecimal janExpected = FLAT_FEE.add(priceOn).add(vat(FLAT_FEE)).add(vat(priceOn));
            BigDecimal febExpected = FLAT_FEE.add(vat(FLAT_FEE));
            if (delta.signum() != 0) {
                febExpected = febExpected.add(delta).add(vat(delta));
            }
            expectedGrand = expectedGrand.add(janExpected).add(febExpected);

            sameAmount(problems, name + " stored quantity", everything, storedQty.get(name));
            sameCount(problems, name + " stored events", onCount[c] + lateCount[c], storedCount.get(name));

            sameAmount(problems, name + " January total", janExpected, invoiceTotal(mine, JAN_1));
            Row janCalls = pick(mine, JAN_1, "USAGE", metricCode);
            if (janCalls == null) {
                problems.add(name + ": January calls line is missing");
            } else {
                sameAmount(problems, name + " January calls amount", priceOn, janCalls.amount());
                sameAmount(problems, name + " January calls quantity", onQty[c], janCalls.quantity());
                sameCount(problems, name + " January calls events", onCount[c], janCalls.eventCount());
                sameAmount(problems, name + " January calls tax", vat(priceOn), janCalls.tax());
            }

            sameAmount(problems, name + " February total", febExpected, invoiceTotal(mine, FEB_1));
            Row late = pick(mine, FEB_1, "LATE_USAGE", metricCode);
            if (delta.signum() == 0) {
                if (late != null) {
                    problems.add(name + ": unexpected late line");
                }
            } else if (late == null) {
                problems.add(name + ": late line is missing");
            } else {
                sameAmount(problems, name + " late amount", delta, late.amount());
                sameAmount(problems, name + " late quantity", lateQty[c], late.quantity());
                sameCount(problems, name + " late events", lateCount[c], late.eventCount());
                sameAmount(problems, name + " late tax", vat(delta), late.tax());
            }
        }
        BigDecimal actualGrand = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total), 0) FROM invoice "
                        + "WHERE customer_id IN (SELECT id FROM customer WHERE external_id LIKE ?)",
                BigDecimal.class, like);
        sameAmount(problems, "grand total of all invoices", expectedGrand, actualGrand);

        // ---- রিপোর্ট ----
        long sent = ON_TIME_UNIQUE + WITHIN_BATCH_DUPLICATES + crossDuplicates + LATE_EVENTS;
        System.out.println();
        System.out.println("=============== QA1 ACCURACY REPORT ===============");
        System.out.println(String.format("events sent                 : %,d", sent));
        System.out.println(String.format("duplicates dropped          : %,d (%.1f%%)",
                p1[1] + crossDuplicates, 100.0 * (p1[1] + crossDuplicates) / sent));
        System.out.println(String.format("late events (after invoice) : %,d (%.1f%%)", p3[3], 100.0 * p3[3] / sent));
        System.out.println(String.format("events stored               : %,d", stored));
        System.out.println(String.format("invoices                    : %,d", invoiceCount));
        System.out.println(String.format("ingestion + run 1 time      : %,d ms", ingestMillis));
        System.out.println(String.format("billing run 1 (%d invoices) : %,d ms", run1.invoicesCreated(), run1.durationMillis()));
        System.out.println(String.format("billing run 2 (%d invoices) : %,d ms", run2.invoicesCreated(), run2.durationMillis()));
        System.out.println("expected grand total        : " + expectedGrand);
        System.out.println("actual grand total          : " + actualGrand);
        System.out.println("mismatches                  : " + problems.size());
        System.out.println("===================================================");

        assertTrue(problems.isEmpty(),
                () -> problems.size() + " mismatches, first ones: " + problems.stream().limit(10).toList());
    }
}