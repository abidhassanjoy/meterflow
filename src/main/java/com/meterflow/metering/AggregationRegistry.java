package com.meterflow.metering;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Spring নিজে সব AggregationStrategy খুঁজে এনে এখানে দেয়, তাই নতুন class যোগ করলেই এখানে চলে আসে। */
@Component
class AggregationRegistry {

    private final Map<String, AggregationStrategy> byType = new TreeMap<>();

    AggregationRegistry(List<AggregationStrategy> strategies) {
        for (AggregationStrategy s : strategies) {
            byType.put(s.type(), s);
        }
    }

    Optional<AggregationStrategy> find(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byType.get(type.toUpperCase(Locale.ROOT)));
    }

    Set<String> types() {
        return byType.keySet();
    }
}