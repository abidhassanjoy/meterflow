package com.meterflow.metering;

import org.springframework.stereotype.Component;

@Component
class LatestAggregation implements AggregationStrategy {

    @Override
    public String type() {
        return "LATEST";
    }

    @Override
    public SqlExpr expression(String property) {
        // সময় অনুযায়ী সাজিয়ে সবচেয়ে নতুন event-এর quantity (সময় সমান হলে বড় id)
        return SqlExpr.of("COALESCE((array_agg(quantity ORDER BY occurred_at DESC, id DESC))[1], 0)");
    }
}