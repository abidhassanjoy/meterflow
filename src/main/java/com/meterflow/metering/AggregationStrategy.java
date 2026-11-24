package com.meterflow.metering;

import java.util.Arrays;
import java.util.List;

/**
 * একটা aggregation পদ্ধতি (SUM, COUNT ...)। প্রতিটা পদ্ধতি আলাদা class (Strategy pattern)।
 * নতুন পদ্ধতি চাইলে শুধু নতুন একটা @Component class লিখলেই হয়, পুরনো কোড বদলাতে হয় না।
 */
public interface AggregationStrategy {

    /** metric-এ যে নামে লেখা হবে, যেমন "SUM" */
    String type();

    /** এই পদ্ধতির SQL হিসাব। property লাগলে (যেমন UNIQUE_COUNT) সেটা দেওয়া হবে */
    SqlExpr expression(String property);

    /** property ছাড়া চলে না কি না */
    default boolean needsProperty() {
        return false;
    }

    /** SQL-এর টুকরো আর তার ? জায়গাগুলোর মান */
    record SqlExpr(String sql, List<Object> params) {
        public static SqlExpr of(String sql, Object... params) {
            return new SqlExpr(sql, List.copyOf(Arrays.asList(params)));
        }
    }
}