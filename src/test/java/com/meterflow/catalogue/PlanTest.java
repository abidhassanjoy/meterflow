package com.meterflow.catalogue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "amount was " + actual);
    }

    private static List<PriceTier> tiers() {
        return List.of(
                new PriceTier(bd("1000"), bd("0.10")),
                new PriceTier(bd("10000"), bd("0.08")),
                new PriceTier(null, bd("0.05")));
    }

    // ---------- Free allowance ----------

    @Test
    void freeAllowanceMakesFirstUnitsFree() {
        FreeAllowanceCharge c = new FreeAllowanceCharge(bd("1000"), new PerUnitCharge(bd("0.05")));
        assertAmount("0.00", c.calculate(bd("0")).amount());
        assertAmount("0.00", c.calculate(bd("1000")).amount());     // ঠিক সীমায় এখনও বিনামূল্যে
        assertAmount("0.05", c.calculate(bd("1001")).amount());     // ১ ইউনিট বিল হলো
        assertAmount("25.00", c.calculate(bd("1500")).amount());    // ৫০০ x ০.০৫
    }

    @Test
    void freeAllowanceWorksWithGraduatedTiers() {
        FreeAllowanceCharge c = new FreeAllowanceCharge(bd("1000"), new GraduatedCharge(tiers()));
        // ১০০০ বিনামূল্যে, বাকি ১ ইউনিট প্রথম ধাপে (০.১০), কারণ বিলযোগ্য গণনা নতুন করে শুরু
        assertAmount("0.10", c.calculate(bd("1001")).amount());
    }

    @Test
    void freeAllowanceExplainsItself() {
        ChargeResult r = new FreeAllowanceCharge(bd("1000"), new PerUnitCharge(bd("0.05"))).calculate(bd("1500"));
        assertEquals("1000 free units, then 500 billable: 500 units x 0.05", r.rule());
    }

    // ---------- Minimum commitment ----------

    @Test
    void minimumCommitmentRaisesSmallBills() {
        MinimumCommitmentCharge c = new MinimumCommitmentCharge(bd("100"), new PerUnitCharge(bd("0.05")));
        assertAmount("100.00", c.calculate(bd("0")).amount());       // usage নেই, তবু ১০০
        assertAmount("100.00", c.calculate(bd("1000")).amount());    // হিসাব ৫০, ১০০-এ উঠল
    }

    @Test
    void minimumCommitmentLeavesBigBillsAlone() {
        MinimumCommitmentCharge c = new MinimumCommitmentCharge(bd("100"), new PerUnitCharge(bd("0.05")));
        assertAmount("150.00", c.calculate(bd("3000")).amount());    // হিসাব ১৫০ > ১০০
        assertAmount("100.00", c.calculate(bd("2000")).amount());    // ঠিক সমান
    }

    @Test
    void minimumCommitmentExplainsWhenApplied() {
        ChargeResult r = new MinimumCommitmentCharge(bd("100"), new PerUnitCharge(bd("0.05"))).calculate(bd("1000"));
        assertEquals("minimum commitment 100 (usage came to 50)", r.rule());
    }

    // ---------- Plan (Composite) ----------

    /** ৫০০ টাকা flat + প্রথম ১০০০ API call বিনামূল্যে তারপর প্রতি call ০.০৫ + প্রতি সিটে ২০০ */
    private static Plan samplePlan() {
        return new Plan("pro", 1, "BDT", List.of(
                new PlanLine("Platform fee", null, new FlatCharge(bd("500"))),
                new PlanLine("API calls", "api_calls",
                        new FreeAllowanceCharge(bd("1000"), new PerUnitCharge(bd("0.05")))),
                new PlanLine("Seats", "seats", new PerSeatCharge(bd("200")))));
    }

    @Test
    void planAddsUpAllLines() {
        PlanPrice p = samplePlan().price(Map.of("api_calls", bd("3000"), "seats", bd("10")));
        assertEquals(3, p.lines().size());
        assertAmount("500.00", p.lines().get(0).amount());     // flat
        assertAmount("100.00", p.lines().get(1).amount());     // (3000 - 1000) x 0.05
        assertAmount("2000.00", p.lines().get(2).amount());    // 10 x 200
        assertAmount("2600.00", p.total());
    }

    @Test
    void planTotalEqualsSumOfLines() {
        PlanPrice p = samplePlan().price(Map.of("api_calls", bd("12345"), "seats", bd("3")));
        BigDecimal sum = p.lines().stream().map(PlanPrice.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(p.total()));
    }

    @Test
    void missingMetricCountsAsZero() {
        PlanPrice p = samplePlan().price(Map.of());     // কোনো usage নেই
        assertAmount("500.00", p.total());              // শুধু flat fee বাকি
    }

    @Test
    void everyPlanLineExplainsItself() {
        PlanPrice p = samplePlan().price(Map.of("api_calls", bd("3000"), "seats", bd("10")));
        for (PlanPrice.Line line : p.lines()) {
            assertNotNull(line.rule());
            assertFalse(line.rule().isBlank());
        }
        assertEquals("10 seats x 200", p.lines().get(2).rule());
    }

    @Test
    void planCanBeUsedLikeAnyOtherCharge() {
        Charge asCharge = samplePlan();
        // সব metric-লাইন একই quantity পায়: ৩০০০ ইউনিট
        ChargeResult r = asCharge.calculate(bd("3000"));
        assertNotNull(r.rule());
        // flat 500 + free allowance (3000-1000) x 0.05 = 100 + 3000 সিট x 200 = 600000
        assertAmount("600600.00", r.amount());
    }

    @Test
    void planWithMinimumCommitmentInsideALine() {
        Plan plan = new Plan("starter", 1, "BDT", List.of(
                new PlanLine("API calls", "api_calls",
                        new MinimumCommitmentCharge(bd("200"), new PerUnitCharge(bd("0.05"))))));
        assertAmount("200.00", plan.price(Map.of("api_calls", bd("100"))).total());     // ন্যূনতম ২০০
        assertAmount("250.00", plan.price(Map.of("api_calls", bd("5000"))).total());    // হিসাব ২৫০ > ২০০
    }

    @Test
    void invalidPlansAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Plan("x", 1, "EUR", List.of(
                new PlanLine("a", null, new FlatCharge(bd("1"))))));          // EUR নয়
        assertThrows(IllegalArgumentException.class, () -> new Plan("x", 1, "BDT", List.of()));   // লাইন নেই
        assertThrows(IllegalArgumentException.class, () -> new Plan("", 1, "BDT", List.of(
                new PlanLine("a", null, new FlatCharge(bd("1"))))));          // code ফাঁকা
        assertThrows(IllegalArgumentException.class, () -> new Plan("x", 0, "BDT", List.of(
                new PlanLine("a", null, new FlatCharge(bd("1"))))));          // version ০
    }
}