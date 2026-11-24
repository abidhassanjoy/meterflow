package com.meterflow.metering;

import org.springframework.stereotype.Component;

@Component
class CountAggregation implements AggregationStrategy {

    @Override
    public String type() {
        return "COUNT";
    }

    @Override
    public SqlExpr expression(String property) {
        return SqlExpr.of("COUNT(*)");
    }
}