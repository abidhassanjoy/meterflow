package com.meterflow.catalogue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PlanServiceTest {

    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;

    final List<String> codes = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String code : codes) {
            jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", code);
            jdbc.update("DELETE FROM plan WHERE code = ?", code);
        }
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private String newCode() {
        String code = "plan_" + UUID.randomUUID();
        codes.add(code);
        return code;
    }

    /** ৫০০ flat + প্রথম ১০০০ call বিনামূল্যে তারপর graduated + প্রতি সিটে ২০০ */
    private PlanService.PlanSpec proSpec(String code, String flatFee) {
        return new PlanService.PlanSpec(code, "Pro", null, "BDT", List.of(
                new PlanService.LineSpec("Platform fee", null,
                        Map.of("type", "FLAT", "fee", flatFee)),
                new PlanService.LineSpec("API calls", "api_calls",
                        Map.of("type", "FREE_ALLOWANCE", "freeUnits", "1000",
                                "inner", Map.of("type", "GRADUATED", "tiers", List.of(
                                        Map.of("upTo", "10000", "unitPrice", "0.10"),
                                        Map.of("unitPrice", "0.05"))))),
                new PlanService.LineSpec("Seats", "seats",
                        Map.of("type", "PER_SEAT", "seatPrice", "200"))));
    }

    @Test
    void planCreatedFromConfigIsPricedCorrectly() {
        String code = newCode();
        plans.create(proSpec(code, "500"));

        PlanPrice p = plans.price(code, null, Map.of("api_calls", bd("3000"), "seats", bd("10")));

        assertEquals(3, p.lines().size());
        assertEquals(0, bd("500.00").compareTo(p.lines().get(0).amount()));
        // ৩০০০ - ১০০০ বিনামূল্যে = ২০০০ বিলযোগ্য, সব প্রথম ধাপে (০.১০) = ২০০
        assertEquals(0, bd("200.00").compareTo(p.lines().get(1).amount()));
        assertEquals(0, bd("2000.00").compareTo(p.lines().get(2).amount()));
        assertEquals(0, bd("2700.00").compareTo(p.total()));
    }

    @Test
    void storedPlanComesBackUnchanged() {
        String code = newCode();
        plans.create(proSpec(code, "500"));
        PlanService.PlanSpec loaded = plans.load(code, null).orElseThrow();
        assertEquals(1, loaded.version());
        assertEquals("BDT", loaded.currency());
        assertEquals(3, loaded.lines().size());
        assertEquals("Platform fee", loaded.lines().get(0).description());
        assertEquals("API calls", loaded.lines().get(1).description());
    }

    @Test
    void editingAPlanCreatesANewVersionAndKeepsTheOld() {
        String code = newCode();
        plans.create(proSpec(code, "500"));
        PlanService.PlanSpec v2 = plans.create(proSpec(code, "700"));
        assertEquals(2, v2.version());

        // পুরনো version এখনও ৫০০ ফি-তে, নতুনটা ৭০০-তে
        PlanPrice old = plans.price(code, 1, Map.of());
        PlanPrice latest = plans.price(code, null, Map.of());
        assertEquals(0, bd("500.00").compareTo(old.total()));
        assertEquals(0, bd("700.00").compareTo(latest.total()));
    }

    @Test
    void unknownChargeTypeIsRejected() {
        String code = newCode();
        PlanService.PlanSpec bad = new PlanService.PlanSpec(code, "Bad", null, "BDT", List.of(
                new PlanService.LineSpec("x", null, Map.of("type", "MAGIC"))));
        assertThrows(ResponseStatusException.class, () -> plans.create(bad));
        assertTrue(plans.load(code, null).isEmpty());     // কিছুই সংরক্ষিত হয়নি
    }

    @Test
    void missingChargeFieldIsRejected() {
        String code = newCode();
        PlanService.PlanSpec bad = new PlanService.PlanSpec(code, "Bad", null, "BDT", List.of(
                new PlanService.LineSpec("x", "api_calls", Map.of("type", "PER_UNIT"))));   // unitPrice নেই
        assertThrows(ResponseStatusException.class, () -> plans.create(bad));
    }

    @Test
    void wrongCurrencyIsRejected() {
        String code = newCode();
        PlanService.PlanSpec bad = new PlanService.PlanSpec(code, "Bad", null, "EUR", List.of(
                new PlanService.LineSpec("x", null, Map.of("type", "FLAT", "fee", "10"))));
        assertThrows(ResponseStatusException.class, () -> plans.create(bad));
    }

    @Test
    void unknownPlanIsNotFound() {
        assertThrows(ResponseStatusException.class, () -> plans.price("nope_" + UUID.randomUUID(), null, Map.of()));
    }

    @Test
    void negativeUsageIsRejected() {
        String code = newCode();
        plans.create(proSpec(code, "500"));
        assertThrows(ResponseStatusException.class, () -> plans.price(code, null, Map.of("seats", bd("-1"))));
    }
}