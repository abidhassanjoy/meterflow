package com.meterflow.invoicing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TaxRuleTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static final TaxRule VAT = new TaxRule("vat", "VAT", bd("0.15"));

    @Test
    void taxRoundsHalfUpToThePaisa() {
        assertEquals(0, bd("15.00").compareTo(VAT.taxOn(bd("100"))));
        assertEquals(0, bd("0.02").compareTo(VAT.taxOn(bd("0.10"))));    // 0.015 উপরে
        assertEquals(0, bd("0.05").compareTo(VAT.taxOn(bd("0.30"))));    // 0.045 উপরে
        assertEquals(0, bd("1.43").compareTo(VAT.taxOn(bd("9.50"))));    // 1.425 উপরে
    }

    @Test
    void negativeAmountsRoundTheSameWayAsPositiveOnes() {
        assertEquals(0, bd("-0.02").compareTo(VAT.taxOn(bd("-0.10"))));
        assertEquals(0, bd("-52.50").compareTo(VAT.taxOn(bd("-350"))));
    }

    @Test
    void labelShowsNameAndPercent() {
        assertEquals("VAT 15%", VAT.label());
        assertEquals("GST 7.5%", new TaxRule("gst", "GST", bd("0.075")).label());
    }

    @Test
    void noneChargesNothingAndBadRatesAreRefused() {
        assertEquals(0, BigDecimal.ZERO.compareTo(TaxRule.NONE.taxOn(bd("999"))));
        assertThrows(IllegalArgumentException.class, () -> new TaxRule("x", "X", bd("-0.1")));
        assertThrows(IllegalArgumentException.class, () -> new TaxRule("x", "X", bd("1.5")));
    }
}