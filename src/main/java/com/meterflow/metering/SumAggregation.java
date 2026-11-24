package com.meterflow.metering;

import org.springframework.stereotype.Component;

@Component
class SumAggregation implements AggregationStrategy {

    @Override
    public String type() {
        return "SUM";
    }

    @Override
    public SqlExpr expression(String property) {
        return SqlExpr.of("COALESCE(SUM(quantity), 0)");
    }
}