package com.meterflow.catalogue;

import java.math.BigDecimal;
import java.util.List;

/** একটা plan-এর পুরো হিসাব: প্রতিটা লাইন আলাদা, আর মোট। */
public record PlanPrice(String planCode, String currency, List<Line> lines, BigDecimal total) {

    /** invoice-এর একটা line-এর মতো: কী, কত usage, কোন নিয়মে, কত টাকা। */
    public record Line(String description, String metricCode, BigDecimal quantity,
                       String rule, BigDecimal amount) {
    }
}