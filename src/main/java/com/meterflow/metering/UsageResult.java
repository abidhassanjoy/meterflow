package com.meterflow.metering;

import java.math.BigDecimal;
import java.time.LocalDate;

/** একটা metric-এর হিসাবের ফলাফল। eventCount দিয়ে বোঝা যায় কয়টা event থেকে এই মান এসেছে। */
public record UsageResult(
        String customer,
        String metric,
        String aggregation,
        LocalDate from,
        LocalDate to,
        BigDecimal value,
        long eventCount
) {
}