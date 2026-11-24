package com.meterflow.metering;

import java.util.Map;

/**
 * একটা billable metric-এর সংজ্ঞা।
 * code: metric-এর নাম, aggregation: SUM/COUNT/MAX/UNIQUE_COUNT/LATEST,
 * property: UNIQUE_COUNT-এ কোন property গুনব, eventName: কোন event-এর নাম গুনব (ফাঁকা হলে code-ই),
 * filters: যেমন {"region":"asia"} মানে শুধু এই event গুলো।
 */
public record MetricDefinition(
        Long id,
        String code,
        String name,
        String aggregation,
        String property,
        String eventName,
        Map<String, String> filters
) {
}