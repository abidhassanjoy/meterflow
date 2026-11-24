package com.meterflow.subscriptions;

import com.meterflow.catalogue.PlanService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class SubscriptionService {

    // ---------- request আর response ----------

    public record StartRequest(String customer, String planCode, Integer planVersion,
                               LocalDate startDate, Integer trialDays, Boolean convertToPaid) {
    }

    /** when: "NOW" (এখনই, proration সহ) অথবা "PERIOD_END" (period শেষে)। না দিলে NOW। */
    public record ChangePlanRequest(String planCode, Integer planVersion, String when) {
    }

    public record View(Long id, String customer, String planCode, int planVersion, String status,
                       LocalDate periodStart, LocalDate periodEnd, LocalDate trialEndsAt,
                       String pendingPlanCode, Integer pendingPlanVersion, long version) {
    }

    public record Adjustment(BigDecimal amount, String description, boolean billed) {
    }

    private record CustomerRef(long id, String currency) {
    }

    private record PlanRef(long id, String code, int version, String currency) {
    }

    private final JdbcTemplate jdbc;
    private final SubscriptionRepository repo;
    private final PlanService plans;

    public SubscriptionService(JdbcTemplate jdbc, SubscriptionRepository repo, PlanService plans) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.plans = plans;
    }

    // ---------- শুরু ----------

    @Transactional
    public View start(StartRequest r) {
        if (r == null) {
            throw bad("request body is required");
        }
        if (blank(r.customer())) {
            throw bad("customer is required");
        }
        if (blank(r.planCode())) {
            throw bad("planCode is required");
        }
        LocalDate start = r.startDate() == null ? LocalDate.now(ZoneOffset.UTC) : r.startDate();
        int trialDays = r.trialDays() == null ? 0 : r.trialDays();
        boolean convert = r.convertToPaid() == null || r.convertToPaid();

        CustomerRef customer = findCustomer(r.customer());
        PlanRef plan = findPlan(r.planCode(), r.planVersion());
        if (!plan.currency().equals(customer.currency())) {
            throw bad("plan currency " + plan.currency() + " does not match customer currency "
                    + customer.currency());
        }
        if (repo.findLiveByCustomer(customer.id()).isPresent()) {
            throw conflict("customer already has a live subscription");
        }

        Subscription s;
        try {
            s = Subscription.start(customer.id(), plan.id(), start, trialDays, convert);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        try {
            s = repo.insert(s);
        } catch (DuplicateKeyException e) {
            throw conflict("customer already has a live subscription");
        }
        return view(s);
    }

    public View get(long id) {
        return view(load(id));
    }

    // ---------- অবস্থা বদল ----------

    @Transactional
    public View pause(long id) {
        return transition(id, Subscription::pause);
    }

    @Transactional
    public View resume(long id) {
        return transition(id, Subscription::resume);
    }

    @Transactional
    public View cancel(long id) {
        return transition(id, Subscription::cancel);
    }

    private View transition(long id, Consumer<Subscription> action) {
        Subscription s = load(id);
        action.accept(s);          // নিয়ম ভাঙলে এখানেই IllegalTransitionException
        repo.update(s);
        return view(load(id));
    }

    // ---------- plan বদল ----------

    @Transactional
    public View changePlan(long id, ChangePlanRequest r) {
        return changePlan(id, r, LocalDate.now(ZoneOffset.UTC));
    }

    /** today আলাদা করে দেওয়া যায়, যাতে test যেকোনো তারিখ ধরে চালানো যায়। */
    @Transactional
    public View changePlan(long id, ChangePlanRequest r, LocalDate today) {
        if (r == null || blank(r.planCode())) {
            throw bad("planCode is required");
        }
        String when = r.when() == null ? "NOW" : r.when().toUpperCase(Locale.ROOT);
        if (!when.equals("NOW") && !when.equals("PERIOD_END")) {
            throw bad("when must be NOW or PERIOD_END");
        }

        Subscription s = load(id);
        PlanRef newPlan = findPlan(r.planCode(), r.planVersion());
        String customerCurrency = jdbc.queryForObject(
                "SELECT currency FROM customer WHERE id = ?", String.class, s.customerId()).trim();
        if (!newPlan.currency().equals(customerCurrency)) {
            throw bad("plan currency " + newPlan.currency() + " does not match customer currency "
                    + customerCurrency);
        }

        if (when.equals("PERIOD_END")) {
            s.changePlanAtPeriodEnd(newPlan.id());
            repo.update(s);
            return view(load(id));
        }

        PlanRef oldPlan = planById(s.planId());
        boolean billable = s.isBillable();     // trial চলাকালে কোনো বিল নেই, তাই proration-ও নেই
        s.changePlanNow(newPlan.id());          // paused বা cancelled হলে এখানে ব্যর্থ হয়

        BigDecimal amount = BigDecimal.ZERO;
        if (billable) {
            amount = prorate(baseFee(newPlan), baseFee(oldPlan), s.remainingDays(today), s.periodLengthDays());
        }
        repo.update(s);                         // version না মিললে এখানে থামে, সমন্বয় লেখা হয় না

        if (amount.signum() != 0) {
            jdbc.update("INSERT INTO subscription_adjustment (subscription_id, amount, description) VALUES (?, ?, ?)",
                    id, amount,
                    "Plan change " + oldPlan.code() + " v" + oldPlan.version() + " to "
                            + newPlan.code() + " v" + newPlan.version() + ": "
                            + s.remainingDays(today) + "/" + s.periodLengthDays() + " days");
        }
        return view(load(id));
    }

    /**
     * বাকি দিনের আনুপাতিক পার্থক্য। শেষে একবারই পয়সা পর্যন্ত গোল (HALF_UP)।
     * নতুন plan দামি হলে ধনাত্মক (charge), সস্তা হলে ঋণাত্মক (credit)।
     */
    static BigDecimal prorate(BigDecimal newFee, BigDecimal oldFee, int remainingDays, int periodDays) {
        return newFee.subtract(oldFee)
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
    }

    /** usage শূন্য ধরে plan-এর দাম, মানে নির্দিষ্ট মাসিক ফি (minimum commitment থাকলে সেটাসহ)। */
    private BigDecimal baseFee(PlanRef plan) {
        return plans.price(plan.code(), plan.version(), Map.of()).total();
    }

    public List<Adjustment> adjustments(long id) {
        load(id);
        return jdbc.query(
                "SELECT amount, description, billed_invoice_id FROM subscription_adjustment "
                        + "WHERE subscription_id = ? ORDER BY id",
                (rs, n) -> new Adjustment(rs.getBigDecimal("amount"), rs.getString("description"),
                        rs.getObject("billed_invoice_id") != null),
                id);
    }

    // ---------- trial নিজে থেকে শেষ ----------

    /**
     * trial শেষের দিন বা তার পরে যেগুলোর, সেগুলো চালু (ACTIVE) বা বাতিল (CANCELLED) করে।
     * আবার চালালে কিছু বদলায় না, কারণ আর TRIAL অবস্থায় নেই। কতগুলো বদলালো সেটা ফেরত দেয়।
     */
    @Transactional
    public int processTrials(LocalDate today) {
        if (today == null) {
            throw bad("date is required");
        }
        List<Long> due = jdbc.query(
                "SELECT id FROM subscription WHERE status = 'TRIAL' AND trial_ends_at <= ? ORDER BY id",
                (rs, n) -> rs.getLong(1), Date.valueOf(today));
        int done = 0;
        for (long id : due) {
            Subscription s = repo.find(id).orElse(null);
            if (s == null || s.status() != SubscriptionStatus.TRIAL) {
                continue;
            }
            if (s.convertToPaid()) {
                s.convertTrial();
            } else {
                s.expireTrial();
            }
            try {
                repo.update(s);
                done++;
            } catch (OptimisticLockingFailureException e) {
                // মাঝখানে অন্য কেউ বদলে দিয়েছে, পরের বারে আবার চেষ্টা হবে
            }
        }
        return done;
    }
    // ---------- billing-এর জন্য ----------

    /** যেসব চালু subscription-এর period asOf-এর আগে শেষ হয়েছে। */
    public List<Long> dueForBilling(LocalDate asOf) {
        return jdbc.query(
                "SELECT id FROM subscription WHERE status = 'ACTIVE' AND current_period_end < ? ORDER BY id",
                (rs, n) -> rs.getLong(1), Date.valueOf(asOf));
    }

    /** invoice হয়ে যাওয়ার পর পরের period শুরু করে (অপেক্ষমাণ plan থাকলে সেটা বসে)। */
    @Transactional
    public void renewAfterBilling(long id) {
        Subscription s = load(id);
        s.renew();
        repo.update(s);
    }     /** এখনও বিল না হওয়া সমন্বয় গুলো এই invoice-এর নামে চিহ্নিত করে ফেরত দেয়। একই সমন্বয় দুবার যায় না। */
    @Transactional
    public List<Adjustment> claimAdjustments(long subscriptionId, long invoiceId) {
        jdbc.update("UPDATE subscription_adjustment SET billed_invoice_id = ? "
                + "WHERE subscription_id = ? AND billed_invoice_id IS NULL", invoiceId, subscriptionId);
        return jdbc.query(
                "SELECT amount, description FROM subscription_adjustment "
                        + "WHERE subscription_id = ? AND billed_invoice_id = ? ORDER BY id",
                (rs, n) -> new Adjustment(rs.getBigDecimal("amount"), rs.getString("description"), true),
                subscriptionId, invoiceId);
    }
    // ---------- সহায়ক ----------

    private Subscription load(long id) {
        return repo.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown subscription: " + id));
    }

    private View view(Subscription s) {
        String customer = jdbc.queryForObject(
                "SELECT external_id FROM customer WHERE id = ?", String.class, s.customerId());
        PlanRef plan = planById(s.planId());
        PlanRef pending = s.pendingPlanId() == null ? null : planById(s.pendingPlanId());
        return new View(s.id(), customer, plan.code(), plan.version(), s.status().name(),
                s.periodStart(), s.periodEnd(), s.trialEndsAt(),
                pending == null ? null : pending.code(),
                pending == null ? null : pending.version(),
                s.version());
    }

    private CustomerRef findCustomer(String externalId) {
        List<CustomerRef> rows = jdbc.query("SELECT id, currency FROM customer WHERE external_id = ?",
                (rs, n) -> new CustomerRef(rs.getLong("id"), rs.getString("currency").trim()), externalId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown customer: " + externalId);
        }
        return rows.get(0);
    }

    /** version না দিলে সর্বশেষ version। */
    private PlanRef findPlan(String code, Integer version) {
        List<PlanRef> rows = jdbc.query(
                "SELECT id, code, version, currency FROM plan WHERE code = ? "
                        + (version == null ? "ORDER BY version DESC LIMIT 1" : "AND version = ?"),
                (rs, n) -> new PlanRef(rs.getLong("id"), rs.getString("code"), rs.getInt("version"),
                        rs.getString("currency").trim()),
                version == null ? new Object[]{code} : new Object[]{code, version});
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown plan: " + code);
        }
        return rows.get(0);
    }

    private PlanRef planById(long id) {
        List<PlanRef> rows = jdbc.query("SELECT id, code, version, currency FROM plan WHERE id = ?",
                (rs, n) -> new PlanRef(rs.getLong("id"), rs.getString("code"), rs.getInt("version"),
                        rs.getString("currency").trim()), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown plan id: " + id);
        }
        return rows.get(0);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}