package com.meterflow.metering;

import org.springframework.stereotype.Component;

@Component
class UniqueCountAggregation implements AggregationStrategy {

    @Override
    public String type() {
        return "UNIQUE_COUNT";
    }

    @Override
    public boolean needsProperty() {
        return true;   // কোন property-র আলাদা মান গুনব, সেটা বলে দিতে হবে
    }

    @Override
    public SqlExpr expression(String property) {
        return SqlExpr.of("COUNT(DISTINCT properties ->> CAST(? AS text))", property);
    }
}