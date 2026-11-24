package com.meterflow.invoicing;

import com.meterflow.catalogue.PlanPrice;
import com.meterflow.catalogue.PlanService;
import com.meterflow.metering.BillingUsageService;
import com.meterflow.subscriptions.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class InvoiceService {

    public record GenerateRequest(Long subscriptionId, LocalDate asOf) {
    }

    /** আগে হয়ে যাওয়া একটা invoice, যার period-এ দেরিতে আসা event পড়েছে। */
    private record OldInvoice(long id, LocalDate periodStart, LocalDate periodEnd,
                              String planCode, Integer planVersion) {
    }

    private final JdbcTemplate jdbc;
    private final InvoiceRepository repo;
    private final SubscriptionService subscriptions;
    private final PlanService plans;
    private final BillingUsageService usage;
    private final CreditService credits;

    public InvoiceService(JdbcTemplate jdbc, InvoiceRepository repo, SubscriptionService subscriptions,
                          PlanService plans, BillingUsageService usage, CreditService credits) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.usage = usage;
        this.credits = credits;
    }

    /**
     * একটা subscription-এর শেষ হয়ে যাওয়া period-এর invoice বানায়। সব কাজ একটাই transaction-এ:
     * draft বানানো, event চিহ্নিত করা, line হিসাব, কর, সমন্বয়, late event, credit কাটা, finalise,
     * আর পরের period শুরু। মাঝপথে কিছু ভাঙলে সব ফিরে যায়, তাই আবার চালালে নিরাপদে শুরু থেকে হয়।
     * period এখনও শেষ না হলে ফাঁকা (Optional.empty) ফেরত দেয়।
     */
    @Transactional
    public Optional<Invoice> generate(long subscriptionId, LocalDate asOf) {
        if (asOf == null) {
            throw bad("asOf is required");
        }

        // সারি তালাবদ্ধ: billing চলার সময় আর কেউ এই subscription বদলাতে পারবে না
        List<Long> locked = jdbc.query("SELECT id FROM subscription WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), subscriptionId);
        if (locked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown subscription: " + subscriptionId);
        }

        SubscriptionService.View sub = subscriptions.get(subscriptionId);
        if (!"ACTIVE".equals(sub.status())) {
            throw conflict("only ACTIVE subscriptions are billed (this one is " + sub.status() + ")");
        }
        if (!sub.periodEnd().isBefore(asOf)) {
            return Optional.empty();       // period এখনও শেষ হয়নি
        }

        long customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?",
                Long.class, sub.customer());
        PlanService.PlanSpec spec = plans.load(sub.planCode(), sub.planVersion())
                .orElseThrow(() -> new IllegalStateException("plan not found: " + sub.planCode()));
        Set<String> metricCodes = metricCodesOf(spec);

        // ধাপ ১: draft invoice। একই (customer, period) আগে থাকলে এখানে থামি
        Optional<Long> draft = repo.insertDraft(customerId, subscriptionId,
                sub.periodStart(), sub.periodEnd(), spec.currency(), sub.planCode(), sub.planVersion());
        if (draft.isEmpty()) {
            throw conflict("this period was already invoiced");
        }
        long invoiceId = draft.get();

        // ধাপ ২: period-এর সাধারণ event আর আগের period-এর দেরিতে আসা event গুলো এই invoice-এর নামে চিহ্নিত
        usage.claimEvents(customerId, sub.periodStart(), sub.periodEnd(), invoiceId);
        usage.claimLateEvents(customerId, sub.periodStart(), invoiceId);

        // ধাপ ৩: প্রতিটা metric-এর মান শুধু চিহ্নিত সাধারণ event থেকে
        Map<String, BillingUsageService.Claimed> claimed = new HashMap<>();
        Map<String, BigDecimal> values = new HashMap<>();
        for (String code : metricCodes) {
            BillingUsageService.Claimed c = usage.aggregate(customerId, code, invoiceId);
            claimed.put(code, c);
            values.put(code, c.value());
        }

        // ধাপ ৪: plan থেকে দাম, প্রতিটা line নিজের নিয়ম আর কর ব্যাখ্যা করে
        TaxRule tax = taxRuleFor(customerId);
        PlanPrice price = plans.price(sub.planCode(), sub.planVersion(), values);
        Invoice.Builder builder = Invoice.builder()
                .id(invoiceId)
                .customer(sub.customer())
                .subscriptionId(subscriptionId)
                .period(sub.periodStart(), sub.periodEnd())
                .currency(price.currency());
        for (PlanPrice.Line l : price.lines()) {
            long events = l.metricCode() == null ? 0 : claimed.get(l.metricCode()).eventCount();
            builder.line(new InvoiceLine(l.description(), l.metricCode(), l.quantity(), events,
                    shorten(l.rule(), 500), l.amount()).withTax(tax));
        }

        // ধাপ ৫: এখনও বিল না হওয়া proration সমন্বয়, একবারই এই invoice-এর নামে চিহ্নিত হয়
        for (SubscriptionService.Adjustment a : subscriptions.claimAdjustments(subscriptionId, invoiceId)) {
            builder.line(adjustmentLine(a).withTax(tax));
        }

        // ধাপ ৬: দেরিতে আসা event-এর জন্য পুরনো invoice আবার হিসাব করে পার্থক্য
        for (InvoiceLine l : lateUsageLines(customerId, invoiceId, tax)) {
            builder.line(l);
        }
        Invoice built = builder.build();

        // ধাপ ৭: prepaid credit আগে কাটা, তারপর বাকিটা পাওনা
        BigDecimal credit = credits.settle(customerId, invoiceId, built.total());
        BigDecimal due = built.total().signum() > 0 ? built.total().subtract(credit) : BigDecimal.ZERO;

        // ধাপ ৮: সংরক্ষণ আর finalise, তারপর subscription-এর পরের period
        repo.insertLines(invoiceId, built.lines());
        repo.finalise(invoiceId, built.subtotal(), built.tax(), built.total(), credit, due);
        subscriptions.renewAfterBilling(subscriptionId);

        return repo.find(invoiceId);
    }

    public Invoice get(long id) {
        return repo.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown invoice: " + id));
    }

    /**
     * এই invoice যে late event গুলো চিহ্নিত করেছে, সেগুলো কোন পুরনো invoice-এর period-এ পড়ে খুঁজে বের করে।
     * প্রতিটা পুরনো invoice-এর জন্য ওই plan version দিয়ে usage দুবার হিসাব হয় (late event ছাড়া আর সহ),
     * আর প্রতিটা plan line-এর দামের পার্থক্য একটা LATE_USAGE line হয়।
     */
    private List<InvoiceLine> lateUsageLines(long customerId, long invoiceId, TaxRule tax) {
        List<OldInvoice> olds = jdbc.query(
                "SELECT DISTINCT o.id, o.period_start, o.period_end, o.plan_code, o.plan_version "
                        + "FROM usage_event e JOIN invoice o ON o.customer_id = e.customer_id "
                        + "AND o.status <> 'VOID' AND o.id <> e.billed_invoice_id "
                        + "AND (e.occurred_at AT TIME ZONE 'UTC')::date BETWEEN o.period_start AND o.period_end "
                        + "WHERE e.customer_id = ? AND e.billed_invoice_id = ? AND e.late = TRUE "
                        + "ORDER BY o.period_start, o.id",
                (rs, n) -> new OldInvoice(rs.getLong("id"), rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate(), rs.getString("plan_code"),
                        (Integer) rs.getObject("plan_version")),
                customerId, invoiceId);

        List<InvoiceLine> result = new ArrayList<>();
        for (OldInvoice old : olds) {
            if (old.planCode() == null || old.planVersion() == null) {
                throw new IllegalStateException("invoice " + old.id() + " has no plan recorded, cannot restate it");
            }
            PlanService.PlanSpec oldSpec = plans.load(old.planCode(), old.planVersion())
                    .orElseThrow(() -> new IllegalStateException("plan not found: " + old.planCode()));

            Map<String, BigDecimal> beforeValues = new HashMap<>();
            Map<String, BigDecimal> afterValues = new HashMap<>();
            Map<String, Long> beforeCounts = new HashMap<>();
            Map<String, Long> afterCounts = new HashMap<>();
            for (String code : metricCodesOf(oldSpec)) {
                BillingUsageService.Claimed before = usage.aggregateOriginal(
                        customerId, code, old.id(), invoiceId, old.periodStart(), old.periodEnd());
                BillingUsageService.Claimed after = usage.aggregateRestated(
                        customerId, code, old.id(), old.periodStart(), old.periodEnd());
                beforeValues.put(code, before.value());
                afterValues.put(code, after.value());
                beforeCounts.put(code, before.eventCount());
                afterCounts.put(code, after.eventCount());
            }

            PlanPrice priceBefore = plans.price(old.planCode(), old.planVersion(), beforeValues);
            PlanPrice priceAfter = plans.price(old.planCode(), old.planVersion(), afterValues);
            for (int i = 0; i < priceAfter.lines().size(); i++) {
                PlanPrice.Line b = priceBefore.lines().get(i);
                PlanPrice.Line a = priceAfter.lines().get(i);
                BigDecimal delta = a.amount().subtract(b.amount());
                if (delta.signum() == 0) {
                    continue;
                }
                long events = a.metricCode() == null ? 0
                        : afterCounts.get(a.metricCode()) - beforeCounts.get(a.metricCode());
                result.add(new InvoiceLine("LATE_USAGE",
                        shorten("Late usage: " + a.description() + " (" + old.periodStart() + " to "
                                + old.periodEnd() + ")", 300),
                        a.metricCode(), a.quantity().subtract(b.quantity()), events,
                        shorten("re-priced with late events: " + b.amount().toPlainString()
                                + " -> " + a.amount().toPlainString(), 500),
                        delta, null, BigDecimal.ZERO, BigDecimal.ZERO).withTax(tax));
            }
        }
        return result;
    }

    private static Set<String> metricCodesOf(PlanService.PlanSpec spec) {
        Set<String> codes = new LinkedHashSet<>();
        for (PlanService.LineSpec line : spec.lines()) {
            if (line.metric() != null) {
                codes.add(line.metric());
            }
        }
        return codes;
    }

    private TaxRule taxRuleFor(long customerId) {
        List<TaxRule> rows = jdbc.query(
                "SELECT t.code, t.name, t.rate FROM customer c JOIN tax_rule t ON t.code = c.tax_rule_code "
                        + "WHERE c.id = ?",
                (rs, n) -> new TaxRule(rs.getString("code"), rs.getString("name"), rs.getBigDecimal("rate")),
                customerId);
        return rows.isEmpty() ? TaxRule.NONE : rows.get(0);
    }

    private static InvoiceLine adjustmentLine(SubscriptionService.Adjustment a) {
        return new InvoiceLine("ADJUSTMENT", shorten(a.description(), 300), null, BigDecimal.ONE, 0,
                "proration of a mid-period plan change", a.amount(), null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}