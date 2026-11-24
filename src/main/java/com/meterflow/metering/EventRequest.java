package com.meterflow.metering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** একটা usage event। */
public record EventRequest(
        String customer,                 // customer-এর external_id
        String metric,                   // event-এর নাম, যেমন "api_calls"
        BigDecimal quantity,             // কতটা ব্যবহার হলো
        Instant timestamp,               // কখন ঘটেছে
        String idempotencyKey,           // event-এর অনন্য পরিচয়
        Map<String, String> properties   // বাড়তি তথ্য, যেমন {"region":"asia"}। না থাকলেও চলে
) {
    /** properties ছাড়া পুরনো ধাঁচের event (আমাদের আগের test গুলো এটাই ব্যবহার করে)। */
    public EventRequest(String customer, String metric, BigDecimal quantity,
                        Instant timestamp, String idempotencyKey) {
        this(customer, metric, quantity, timestamp, idempotencyKey, null);
    }
}