package com.meterflow.catalogue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChargeTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertAmount(String expected, ChargeResult r) {
        assertEquals(0, bd(expected).compareTo(r.amount()), "amount was " + r.amount());
    }

    /** প্রথম ১০০০ ইউনিট ০.১০, ১০০০ থেকে ১০,০০০ পর্যন্ত ০.০৮, তার পরে ০.০৫ */
    private static List<PriceTier> tiers() {
        return List.of(
                new PriceTier(bd("1000"), bd("0.10")),
                new PriceTier(bd("10000"), bd("0.08")),
                new PriceTier(null, bd("0.05")));
    }

    @Test
    void flatChargeIgnoresUsage() {
        FlatCharge flat = new FlatCharge(bd("500"));
        assertAmount("500.00", flat.calculate(bd("0")));
        ChargeResult big = flat.calculate(bd("999999"));
        assertAmount("500.00", big);
        assertEquals(2, big.amount().scale());
    }

    @Test
    void perUnitMultipliesQuantity() {
        assertAmount("50.00", new PerUnitCharge(bd("0.05")).calculate(bd("1000")));
    }

    @Test
    void perUnitRoundsHalfUpToTwoDecimals() {
        assertAmount("3.70", new PerUnitCharge(bd("0.0003")).calculate(bd("12345")));   // 3.7035
        assertAmount("0.02", new PerUnitCharge(bd("0.0005")).calculate(bd("30")));      // 0.015 উপরে
    }

    @Test
    void graduatedCostsAtTierBoundaries() {
        GraduatedCharge g = new GraduatedCharge(tiers());
        assertAmount("0.00", g.calculate(bd("0")));
        assertAmount("100.00", g.calculate(bd("1000")));      // ঠিক সীমায়, সবই প্রথম ধাপে
        assertAmount("100.08", g.calculate(bd("1001")));      // ১০০ + ১ x ০.০৮
        assertAmount("820.00", g.calculate(bd("10000")));     // ১০০ + ৯০০০ x ০.০৮
        assertAmount("820.05", g.calculate(bd("10001")));     // ৮২০ + ১ x ০.০৫
        assertAmount("1070.00", g.calculate(bd("15000")));    // ৮২০ + ৫০০০ x ০.০৫
    }

    @Test
    void graduatedExplainsItself() {
        ChargeResult r = new GraduatedCharge(tiers()).calculate(bd("1001"));
        assertEquals("graduated tiers: 1000 x 0.1 + 1 x 0.08", r.rule());
    }

    @Test
    void volumePricesEverythingAtTheReachedTier() {
        VolumeCharge v = new VolumeCharge(tiers());
        assertAmount("0.00", v.calculate(bd("0")));
        assertAmount("100.00", v.calculate(bd("1000")));      // ঠিক সীমায়, প্রথম ধাপ
        assertAmount("80.08", v.calculate(bd("1001")));       // ১০০১ x ০.০৮
        assertAmount("800.00", v.calculate(bd("10000")));
        assertAmount("500.05", v.calculate(bd("10001")));     // ১০০০১ x ০.০৫
    }

    @Test
    void packageRoundsUpToWholePackages() {
        PackageCharge p = new PackageCharge(bd("1000"), bd("5"));
        assertAmount("0.00", p.calculate(bd("0")));
        assertAmount("5.00", p.calculate(bd("1")));
        assertAmount("5.00", p.calculate(bd("1000")));
        assertAmount("10.00", p.calculate(bd("1001")));
        assertAmount("15.00", p.calculate(bd("2500")));
    }

    @Test
    void perSeatMultipliesSeats() {
        assertAmount("2000.00", new PerSeatCharge(bd("200")).calculate(bd("10")));
    }

    @Test
    void perSeatRejectsPartialSeats() {
        assertThrows(IllegalArgumentException.class,
                () -> new PerSeatCharge(bd("200")).calculate(bd("2.5")));
    }

    @Test
    void negativeQuantityIsRejectedByEveryCharge() {
        List<Charge> all = List.of(
                new FlatCharge(bd("1")),
                new PerUnitCharge(bd("1")),
                new GraduatedCharge(tiers()),
                new VolumeCharge(tiers()),
                new PackageCharge(bd("1000"), bd("5")),
                new PerSeatCharge(bd("1")));
        for (Charge c : all) {
            assertThrows(IllegalArgumentException.class, () -> c.calculate(bd("-1")), c.type());
        }
    }

    @Test
    void badTiersAreRejected() {
        // কোনো ধাপ নেই
        assertThrows(IllegalArgumentException.class, () -> new GraduatedCharge(List.of()));
        // শেষ ধাপে সীমা আছে
        assertThrows(IllegalArgumentException.class,
                () -> new GraduatedCharge(List.of(new PriceTier(bd("100"), bd("1")))));
        // সীমা বাড়ছে না
        assertThrows(IllegalArgumentException.class, () -> new GraduatedCharge(List.of(
                new PriceTier(bd("100"), bd("1")),
                new PriceTier(bd("50"), bd("1")),
                new PriceTier(null, bd("1")))));
        // মাঝের ধাপ সীমাহীন
        assertThrows(IllegalArgumentException.class, () -> new GraduatedCharge(List.of(
                new PriceTier(null, bd("1")),
                new PriceTier(null, bd("1")))));
        // ঋণাত্মক দাম
        assertThrows(IllegalArgumentException.class, () -> new GraduatedCharge(List.of(
                new PriceTier(null, bd("-1")))));
    }
}