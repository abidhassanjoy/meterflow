package com.meterflow.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** একটা কর নিয়ম, যেমন VAT ১৫% (rate = 0.15)। */
public record TaxRule(String code, String name, BigDecimal rate) {

    /** কর নেই। */
    public static final TaxRule NONE = new TaxRule(null, "no tax", BigDecimal.ZERO);

    public TaxRule {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("tax rate must be between 0 and 1");
        }
    }

    /** টাকার উপর কর, পয়সা পর্যন্ত গোল (৫ বা তার বেশি হলে উপরে, ঋণাত্মক টাকাতেও একই নিয়ম)। */
    public BigDecimal taxOn(BigDecimal amount) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** যেমন "VAT 15%"। */
    public String label() {
        return name + " " + rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }
}