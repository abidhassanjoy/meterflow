package com.meterflow.metering;

import org.springframework.stereotype.Component;

@Component
class MaxAggregation implements AggregationStrategy {

    @Override
    public String type() {
        return "MAX";
    }

    @Override
    public SqlExpr expression(String property) {
        return SqlExpr.of("COALESCE(MAX(quantity), 0)");
    }
}