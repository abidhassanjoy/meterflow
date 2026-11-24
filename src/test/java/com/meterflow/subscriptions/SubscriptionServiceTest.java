package com.meterflow.subscriptions;

import com.meterflow.catalogue.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SubscriptionServiceTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);     // period ১ থেকে ৩০ নভেম্বর (৩০ দিন)
    static final LocalDate NOV_16 = LocalDate.of(2026, 11, 16);   // বাকি ১৫ দিন (আজসহ)

    @Autowired SubscriptionService service;
    @Autowired SubscriptionRepository repo;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    final List<String> customers = new ArrayList<>();
    final List<String> planCodes = new ArrayList<>();
    String customer;
    String basic;   // ফি ৫০০
    String pro;     // ফি ৮০০ (usage লাইনসহ, কিন্তু নির্দিষ্ট ফি ৮০০-ই)
    String usd;     // USD plan

    @BeforeEach
    void setUp() {
        customer = newCustomer("BDT");
        basic = newPlan("BDT", "500", false);
        pro = newPlan("BDT", "800", true);
        usd = newPlan("USD", "10", false);
    }

    @AfterEach
    void cleanUp() {
        for (String c : customers) {
            // subscription মুছলে তার adjustment নিজে মুছে যায়
            jdbc.update("DELETE FROM subscription WHERE customer_id IN (SELECT id FROM customer WHERE external_id = ?)", c);
            jdbc.update("DELETE FROM customer WHERE external_id = ?", c);
        }
        for (String code : planCodes) {
            jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", code);
            jdbc.update("DELETE FROM plan WHERE code = ?", code);
        }
    }

    private String newCustomer(String currency) {
        String external = "sub_" + UUID.randomUUID();
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', ?)", external, currency);
        customers.add(external);
        return external;
    }

    private String newPlan(String currency, String fee, boolean withUsage) {
        String code = "plan_" + UUID.randomUUID();
        planCodes.add(code);
        List<PlanService.LineSpec> lines = new ArrayList<>();
        lines.add(new PlanService.LineSpec("Platform fee", null, Map.of("type", "FLAT", "fee", fee)));
        if (withUsage) {
            lines.add(new PlanService.LineSpec("API calls", "api_calls",
                    Map.of("type", "PER_UNIT", "unitPrice", "0.05")));
        }
        plans.create(new PlanService.PlanSpec(code, "Test plan", null, currency, lines));
        return code;
    }

    private SubscriptionService.StartRequest req(String cust, String plan, LocalDate start, int trialDays,
                                                 boolean convert) {
        return new SubscriptionService.StartRequest(cust, plan, null, start, trialDays, convert);
    }

    private SubscriptionService.ChangePlanRequest change(String plan, String when) {
        return new SubscriptionService.ChangePlanRequest(plan, null, when);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "amount was " + actual);
    }

    // ---------- শুরু ----------

    @Test
    void startWithoutTrialIsActive() {
        SubscriptionService.View v = service.start(req(customer, basic, NOV_1, 0, true));
        assertEquals("ACTIVE", v.status());
        assertEquals(customer, v.customer());
        assertEquals(basic, v.planCode());
        assertEquals(1, v.planVersion());
        assertEquals(NOV_1, v.periodStart());
        assertEquals(LocalDate.of(2026, 11, 30), v.periodEnd());
        assertNull(v.trialEndsAt());
        assertEquals(0, v.version());
    }

    @Test
    void startWithTrialIsInTrial() {
        SubscriptionService.View v = service.start(req(customer, basic, NOV_1, 14, true));
        assertEquals("TRIAL", v.status());
        assertEquals(LocalDate.of(2026, 11, 15), v.trialEndsAt());
    }

    @Test
    void secondLiveSubscriptionIsRefusedUntilTheFirstIsCancelled() {
        long first = service.start(req(customer, basic, NOV_1, 0, true)).id();
        assertThrows(ResponseStatusException.class, () -> service.start(req(customer, pro, NOV_1, 0, true)));

        service.cancel(first);
        assertEquals("ACTIVE", service.start(req(customer, pro, NOV_1, 0, true)).status());
    }

    @Test
    void planInAnotherCurrencyIsRefused() {
        assertThrows(ResponseStatusException.class, () -> service.start(req(customer, usd, NOV_1, 0, true)));
    }

    // ---------- plan বদল ----------

    @Test
    void upgradeNowAddsProratedAdjustment() {
        long id = service.start(req(customer, basic, NOV_1, 0, true)).id();

        SubscriptionService.View after = service.changePlan(id, change(pro, "NOW"), NOV_16);

        assertEquals(pro, after.planCode());
        List<SubscriptionService.Adjustment> adjustments = service.adjustments(id);
        assertEquals(1, adjustments.size());
        assertAmount("150.00", adjustments.get(0).amount());     // (৮০০ - ৫০০) x ১৫/৩০
        assertFalse(adjustments.get(0).billed());                 // এখনও কোনো invoice-এ যায়নি
    }

    @Test
    void downgradeNowAddsNegativeAdjustment() {
        long id = service.start(req(customer, pro, NOV_1, 0, true)).id();

        service.changePlan(id, change(basic, "NOW"), NOV_16);

        List<SubscriptionService.Adjustment> adjustments = service.adjustments(id);
        assertEquals(1, adjustments.size());
        assertAmount("-150.00", adjustments.get(0).amount());    // (৫০০ - ৮০০) x ১৫/৩০
    }

    @Test
    void changeAtPeriodEndAddsNoAdjustmentAndWaits() {
        long id = service.start(req(customer, basic, NOV_1, 0, true)).id();

        SubscriptionService.View after = service.changePlan(id, change(pro, "PERIOD_END"), NOV_16);

        assertEquals(basic, after.planCode());           // এখনও পুরনো plan
        assertEquals(pro, after.pendingPlanCode());      // নতুনটা অপেক্ষায়
        assertTrue(service.adjustments(id).isEmpty());
    }

    @Test
    void changeDuringTrialAddsNoAdjustment() {
        long id = service.start(req(customer, basic, NOV_1, 14, true)).id();

        SubscriptionService.View after = service.changePlan(id, change(pro, "NOW"), LocalDate.of(2026, 11, 5));

        assertEquals(pro, after.planCode());
        assertTrue(service.adjustments(id).isEmpty());   // trial-এ বিল নেই
    }

    @Test
    void pausedSubscriptionCannotChangePlan() {
        long id = service.start(req(customer, basic, NOV_1, 0, true)).id();
        service.pause(id);

        assertThrows(IllegalTransitionException.class, () -> service.changePlan(id, change(pro, "NOW"), NOV_16));
        assertThrows(IllegalTransitionException.class,
                () -> service.changePlan(id, change(pro, "PERIOD_END"), NOV_16));
    }

    @Test
    void changingToTheSamePlanNowIsRefused() {
        long id = service.start(req(customer, basic, NOV_1, 0, true)).id();
        assertThrows(IllegalArgumentException.class, () -> service.changePlan(id, change(basic, "NOW"), NOV_16));
    }

    // ---------- অবস্থা বদল ----------

    @Test
    void pauseResumeAndCancelWorkThroughTheService() {
        long id = service.start(req(customer, basic, NOV_1, 0, true)).id();
        assertEquals("PAUSED", service.pause(id).status());
        assertEquals("ACTIVE", service.resume(id).status());
        assertEquals("CANCELLED", service.cancel(id).status());
        assertThrows(IllegalTransitionException.class, () -> service.resume(id));
    }

    // ---------- optimistic locking ----------

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        long id = service.start(req(customer, basic, NOV_1, 0, true)).id();
        Subscription a = repo.find(id).orElseThrow();
        Subscription b = repo.find(id).orElseThrow();      // দুজন একই version পড়লো

        a.pause();
        repo.update(a);                                    // প্রথমজন সফল
        b.cancel();
        assertThrows(OptimisticLockingFailureException.class, () -> repo.update(b));   // দ্বিতীয়জন ব্যর্থ

        SubscriptionService.View now = service.get(id);
        assertEquals("PAUSED", now.status());              // প্রথমজনের লেখাই টিকে আছে
        assertEquals(1, now.version());
    }

    // ---------- trial ----------

    @Test
    void processTrialsConvertsOrExpiresOnTheTrialEndDate() {
        String other = newCustomer("BDT");
        long keep = service.start(req(customer, basic, NOV_1, 14, true)).id();
        long drop = service.start(req(other, basic, NOV_1, 14, false)).id();

        service.processTrials(LocalDate.of(2026, 11, 14));       // trial এখনও চলছে
        assertEquals("TRIAL", service.get(keep).status());
        assertEquals("TRIAL", service.get(drop).status());

        int done = service.processTrials(LocalDate.of(2026, 11, 15));
        assertTrue(done >= 2);
        SubscriptionService.View converted = service.get(keep);
        assertEquals("ACTIVE", converted.status());
        assertEquals(LocalDate.of(2026, 11, 15), converted.periodStart());
        assertEquals(LocalDate.of(2026, 12, 14), converted.periodEnd());
        assertEquals("CANCELLED", service.get(drop).status());

        service.processTrials(LocalDate.of(2026, 11, 15));       // আবার চালালে কিছু বদলায় না
        assertEquals("ACTIVE", service.get(keep).status());
        assertEquals(LocalDate.of(2026, 11, 15), service.get(keep).periodStart());
    }

    // ---------- অচেনা ----------

    @Test
    void unknownSubscriptionIsNotFound() {
        assertThrows(ResponseStatusException.class, () -> service.get(999_999_999L));
    }
}