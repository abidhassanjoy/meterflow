package com.meterflow.subscriptions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProrationTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "amount was " + actual);
    }

    @Test
    void upgradeHalfwayChargesHalfTheDifference() {
        assertAmount("150.00", SubscriptionService.prorate(bd("800"), bd("500"), 15, 30));
    }

    @Test
    void downgradeGivesANegativeAmount() {
        assertAmount("-150.00", SubscriptionService.prorate(bd("500"), bd("800"), 15, 30));
    }

    @Test
    void fullPeriodChargesTheFullDifference() {
        assertAmount("300.00", SubscriptionService.prorate(bd("800"), bd("500"), 30, 30));
    }

    @Test
    void noDaysLeftChargesNothing() {
        assertAmount("0.00", SubscriptionService.prorate(bd("800"), bd("500"), 0, 30));
    }

    @Test
    void sameFeeChargesNothing() {
        assertAmount("0.00", SubscriptionService.prorate(bd("500"), bd("500"), 12, 30));
    }

    @Test
    void roundsHalfUpAwayFromZero() {
        assertAmount("0.13", SubscriptionService.prorate(bd("1"), bd("0"), 1, 8));      // 0.125
        assertAmount("-0.13", SubscriptionService.prorate(bd("0"), bd("1"), 1, 8));     // -0.125
    }

    @Test
    void thirdsRoundToThePaisa() {
        assertAmount("33.33", SubscriptionService.prorate(bd("100"), bd("0"), 10, 30));
        assertAmount("66.67", SubscriptionService.prorate(bd("100"), bd("0"), 20, 30));
    }
}