package com.meterflow.catalogue;

import java.math.BigDecimal;

/**
 * plan-এর একটা লাইন: কোন metric-এর usage কোন charge দিয়ে হিসাব হবে।
 * metricCode ফাঁকা (null) হলে usage লাগে না (যেমন flat fee)।
 */
public record PlanLine(String description, String metricCode, Charge charge) {

    public PlanLine {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        if (charge == null) {
            throw new IllegalArgumentException("charge is required");
        }
    }

    /** metric-এর পরিমাণ জানা থাকলে সেটা দিয়ে হিসাব। metricCode না থাকলে পরিমাণ ১ ধরা হয়। */
    public ChargeResult price(BigDecimal usage) {
        BigDecimal q = metricCode == null ? BigDecimal.ONE : usage;
        return charge.calculate(q);
    }
}