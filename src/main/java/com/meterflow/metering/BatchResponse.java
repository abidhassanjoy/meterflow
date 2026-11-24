package com.meterflow.metering;

import java.util.List;

public record BatchResponse(
        int received,
        int accepted,       // নতুন সংরক্ষণ হলো (late সহ)
        int late,           // এর মধ্যে কয়টা late
        int duplicates,
        int rejected,
        List<Rejection> errors
) {
    public record Rejection(int index, String reason) {
    }
}