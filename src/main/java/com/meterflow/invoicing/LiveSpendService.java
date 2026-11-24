package com.meterflow.invoicing;

import com.meterflow.catalogue.PlanPrice;
import com.meterflow.catalogue.PlanService;
import com.meterflow.metering.BillingUsageService;
import com.meterflow.subscriptions.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live spend: চলতি period-এর usage থেকে আনুমানিক বিল, কোনো event চিহ্নিত না করে।
 * ফল ৬০ সেকেন্ড cache-এ থাকে। প্রতিবার নতুন হিসাব হলে budget-এর ৫০%, ৮০%, ১০০% পার হয়েছে কি না দেখা হয়
 * আর পার হলে budget_alert-এ একবারই লেখা হয় (unique constraint)।
 *
 * সীমাবদ্ধতা: কর আর proration সমন্বয় এই estimate-এ নেই, তাই এটা invoice-এর subtotal-এর (কর ও সমন্বয় ছাড়া) সমান।
 */
@Service
public class LiveSpendService {

    static final long CACHE_MILLIS = 60_000;
    static final int[] THRESHOLDS = {50, 80, 100};

    public record LineEstimate(String description, String metricCode, BigDecimal quantity,
                               long eventCount, String priceRule, BigDecimal amount) {
    }

    public record Spend(String customer, String currency, String subscriptionStatus,
                        LocalDate periodStart, LocalDate periodEnd, BigDecimal estimatedTotal,
                        BigDecimal budget, BigDecimal percentOfBudget,
                        List<LineEstimate> lines, Instant computedAt) {
    }

    public record Alert(int threshold, BigDecimal spend, BigDecimal budget, Instant createdAt) {
    }

    public record BudgetView(String customer, BigDecimal amount) {
    }

    private record Cached(Spend spend, long at) {
    }

    private record Cust(long id, String currency) {
    }

    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final PlanService plans;
    private final BillingUsageService usage;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LiveSpendService(JdbcTemplate jdbc, SubscriptionService subscriptions, PlanService plans,
                            BillingUsageService usage) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.usage = usage;
    }

    /** cache-এ ৬০ সেকেন্ডের মধ্যের ফল থাকলে সেটা, নইলে নতুন হিসাব। */
    public Spend estimate(String customer) {
        Cached c = cache.get(customer);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_MILLIS) {
            return c.spend();
        }
        return estimateFresh(customer);
    }

    /** cache না দেখে নতুন হিসাব করে, cache নতুন করে ভরে আর alert দেখে। */
    public Spend estimateFresh(String customer) {
        Cust cust = findCustomer(customer);
        Spend spend = compute(cust, customer);
        cache.put(customer, new Cached(spend, System.currentTimeMillis()));
        evaluateAlerts(cust, spend);
        return spend;
    }

    public BudgetView setBudget(String customer, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw bad("amount must be more than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw bad("amount can have at most 2 decimal places");
        }
        Cust cust = findCustomer(customer);
        jdbc.update("INSERT INTO budget (customer_id, amount) VALUES (?, ?) "
                        + "ON CONFLICT (customer_id) DO UPDATE SET amount = EXCLUDED.amount, updated_at = now()",
                cust.id(), amount);
        // budget বদলালে আগের alert আর মানে রাখে না, তাই নতুন budget ধরে আবার দেখা হবে
        jdbc.update("DELETE FROM budget_alert WHERE customer_id = ?", cust.id());
        cache.remove(customer);
        return new BudgetView(customer, amount.setScale(2, RoundingMode.UNNECESSARY));
    }

    public BudgetView getBudget(String customer) {
        Cust cust = findCustomer(customer);
        BigDecimal amount = budgetOf(cust.id());
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no budget set for " + customer);
        }
        return new BudgetView(customer, amount);
    }

    public List<Alert> alerts(String customer) {
        Cust cust = findCustomer(customer);
        return jdbc.query(
                "SELECT threshold, spend, budget, created_at FROM budget_alert WHERE customer_id = ? "
                        + "ORDER BY period_start, threshold",
                (rs, n) -> new Alert(rs.getInt("threshold"), rs.getBigDecimal("spend"),
                        rs.getBigDecimal("budget"), rs.getTimestamp("created_at").toInstant()),
                cust.id());
    }

    /** budget আছে এমন সব customer-এর নতুন হিসাব করে alert দেখে। কতজনের হিসাব হলো সেটা ফেরত দেয়। */
    public int evaluateAll() {
        List<String> customers = jdbc.query(
                "SELECT c.external_id FROM budget b JOIN customer c ON c.id = b.customer_id",
                (rs, n) -> rs.getString(1));
        int done = 0;
        for (String c : customers) {
            try {
                estimateFresh(c);
                done++;
            } catch (RuntimeException e) {
                // subscription নেই বা অন্য সমস্যা: একজনের জন্য বাকিদের আটকানো হয় না
            }
        }
        return done;
    }

    // ---------- ভেতরের হিসাব ----------

    private Spend compute(Cust cust, String customer) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM subscription WHERE customer_id = ? AND status <> 'CANCELLED'",
                (rs, n) -> rs.getLong(1), cust.id());
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no live subscription for " + customer);
        }
        SubscriptionService.View sub = subscriptions.get(ids.get(0));
        PlanService.PlanSpec spec = plans.load(sub.planCode(), sub.planVersion())
                .orElseThrow(() -> new IllegalStateException("plan not found: " + sub.planCode()));

        Set<String> codes = new LinkedHashSet<>();
        for (PlanService.LineSpec line : spec.lines()) {
            if (line.metric() != null) {
                codes.add(line.metric());
            }
        }
        Map<String, BigDecimal> values = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (String code : codes) {
            BillingUsageService.Claimed c = usage.aggregateUnbilled(
                    cust.id(), code, sub.periodStart(), sub.periodEnd());
            values.put(code, c.value());
            counts.put(code, c.eventCount());
        }

        PlanPrice price = plans.price(sub.planCode(), sub.planVersion(), values);
        List<LineEstimate> lines = new ArrayList<>();
        for (PlanPrice.Line l : price.lines()) {
            long events = l.metricCode() == null ? 0 : counts.get(l.metricCode());
            lines.add(new LineEstimate(l.description(), l.metricCode(), l.quantity(), events,
                    l.rule(), l.amount()));
        }

        BigDecimal total = price.total();
        BigDecimal budget = budgetOf(cust.id());
        BigDecimal percent = budget == null ? null
                : total.multiply(BigDecimal.valueOf(100)).divide(budget, 1, RoundingMode.HALF_UP);
        return new Spend(customer, cust.currency(), sub.status(), sub.periodStart(), sub.periodEnd(),
                total, budget, percent, lines, Instant.now());
    }

    private void evaluateAlerts(Cust cust, Spend spend) {
        if (spend.budget() == null) {
            return;
        }
        for (int t : THRESHOLDS) {
            boolean reached = spend.estimatedTotal().multiply(BigDecimal.valueOf(100))
                    .compareTo(spend.budget().multiply(BigDecimal.valueOf(t))) >= 0;
            if (reached) {
                jdbc.update("INSERT INTO budget_alert (customer_id, period_start, threshold, spend, budget) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (customer_id, period_start, threshold) DO NOTHING",
                        cust.id(), java.sql.Date.valueOf(spend.periodStart()), t,
                        spend.estimatedTotal(), spend.budget());
            }
        }
    }

    private BigDecimal budgetOf(long customerId) {
        List<BigDecimal> rows = jdbc.query("SELECT amount FROM budget WHERE customer_id = ?",
                (rs, n) -> rs.getBigDecimal(1), customerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Cust findCustomer(String externalId) {
        List<Cust> rows = jdbc.query("SELECT id, currency FROM customer WHERE external_id = ?",
                (rs, n) -> new Cust(rs.getLong("id"), rs.getString("currency").trim()), externalId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown customer: " + externalId);
        }
        return rows.get(0);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}