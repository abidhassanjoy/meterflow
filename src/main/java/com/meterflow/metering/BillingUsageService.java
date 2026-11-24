package com.meterflow.metering;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * billing-এর জন্য usage। প্রথমে event গুলো invoice-এর নামে চিহ্নিত (claim) করা হয়,
 * তারপর হিসাব হয় শুধু ওই চিহ্নিত event থেকে। তাই একটা event কখনো দুই invoice-এ যেতে পারে না।
 * late event (period-এর invoice হয়ে যাওয়ার পর আসা) সাধারণ usage থেকে আলাদা রাখা হয়,
 * সেগুলোর দাম আসে "আবার হিসাব করে পার্থক্য" পদ্ধতিতে।
 */
@Service
public class BillingUsageService {

    public record Claimed(BigDecimal value, long eventCount) {
    }

    private final JdbcTemplate jdbc;
    private final MetricService metrics;
    private final AggregationRegistry registry;

    public BillingUsageService(JdbcTemplate jdbc, MetricService metrics, AggregationRegistry registry) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.registry = registry;
    }

    /** from থেকে to (দিনসহ, UTC) পর্যন্ত সাধারণ (late নয়) বিল না হওয়া event গুলো invoice-এর নামে চিহ্নিত করে। */
    public int claimEvents(long customerId, LocalDate from, LocalDate to, long invoiceId) {
        return jdbc.update(
                "UPDATE usage_event SET billed_invoice_id = ? "
                        + "WHERE customer_id = ? AND billed_invoice_id IS NULL AND late = FALSE "
                        + "AND occurred_at >= ? AND occurred_at < ?",
                invoiceId, customerId, startOfDay(from), startOfDay(to.plusDays(1)));
    }

    /** before তারিখের আগের, এখনও বিল না হওয়া late event গুলো এই invoice-এর নামে চিহ্নিত করে। */
    public int claimLateEvents(long customerId, LocalDate before, long invoiceId) {
        return jdbc.update(
                "UPDATE usage_event SET billed_invoice_id = ? "
                        + "WHERE customer_id = ? AND billed_invoice_id IS NULL AND late = TRUE "
                        + "AND occurred_at < ?",
                invoiceId, customerId, startOfDay(before));
    }

    /** শুধু এই invoice-এর নামে চিহ্নিত সাধারণ event থেকে metric-এর মান। */
    public Claimed aggregate(long customerId, String metricCode, long invoiceId) {
        return run(customerId, metricCode,
                "(billed_invoice_id = ? AND late = FALSE)",
                List.<Object>of(invoiceId));
    }

    /**
     * পুরনো invoice-এর period-এর usage, নতুন late event ছাড়া: ওই invoice-এর নিজের event
     * আর আগের কোনো invoice-এ ইতিমধ্যে সমন্বয় হয়ে যাওয়া late event।
     */
    public Claimed aggregateOriginal(long customerId, String metricCode, long originalInvoiceId,
                                     long newInvoiceId, LocalDate from, LocalDate to) {
        return run(customerId, metricCode,
                "((billed_invoice_id = ? AND late = FALSE) OR (late = TRUE AND billed_invoice_id IS NOT NULL "
                        + "AND billed_invoice_id <> ? AND occurred_at >= ? AND occurred_at < ?))",
                List.<Object>of(originalInvoiceId, newInvoiceId, startOfDay(from), startOfDay(to.plusDays(1))));
    }

    /** পুরনো invoice-এর period-এর usage, নতুন late event সহ। */
    public Claimed aggregateRestated(long customerId, String metricCode, long originalInvoiceId,
                                     LocalDate from, LocalDate to) {
        return run(customerId, metricCode,
                "((billed_invoice_id = ? AND late = FALSE) OR (late = TRUE AND billed_invoice_id IS NOT NULL "
                        + "AND occurred_at >= ? AND occurred_at < ?))",
                List.<Object>of(originalInvoiceId, startOfDay(from), startOfDay(to.plusDays(1))));
    }
    /** চলতি period-এর এখনও বিল না হওয়া সাধারণ event থেকে মান। কোনো event চিহ্নিত করে না (live spend-এর জন্য)। */
    public Claimed aggregateUnbilled(long customerId, String metricCode, LocalDate from, LocalDate to) {
        return run(customerId, metricCode,
                "(billed_invoice_id IS NULL AND late = FALSE AND occurred_at >= ? AND occurred_at < ?)",
                List.<Object>of(startOfDay(from), startOfDay(to.plusDays(1))));
    }
    private Claimed run(long customerId, String metricCode, String invoiceCondition, List<Object> invoiceParams) {
        MetricDefinition m = metrics.find(metricCode)
                .orElseThrow(() -> new IllegalStateException("metric is not defined: " + metricCode));
        AggregationStrategy strategy = registry.find(m.aggregation())
                .orElseThrow(() -> new IllegalStateException("no aggregation for " + m.aggregation()));
        AggregationStrategy.SqlExpr expr = strategy.expression(m.property());

        StringBuilder sql = new StringBuilder("SELECT ").append(expr.sql())
                .append(", COUNT(*) FROM usage_event")
                .append(" WHERE customer_id = ? AND metric = ? AND ").append(invoiceCondition);

        List<Object> params = new ArrayList<>(expr.params());
        params.add(customerId);
        params.add(m.eventName() != null ? m.eventName() : m.code());
        params.addAll(invoiceParams);

        for (Map.Entry<String, String> f : m.filters().entrySet()) {
            sql.append(" AND properties ->> CAST(? AS text) = CAST(? AS text)");
            params.add(f.getKey());
            params.add(f.getValue());
        }
        return jdbc.queryForObject(sql.toString(),
                (rs, n) -> new Claimed(rs.getBigDecimal(1), rs.getLong(2)),
                params.toArray());
    }

    private static Timestamp startOfDay(LocalDate d) {
        return Timestamp.from(d.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}