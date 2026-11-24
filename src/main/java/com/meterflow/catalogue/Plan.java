package com.meterflow.catalogue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * একটা plan অনেকগুলো লাইন (charge) জুড়ে তৈরি। (Composite pattern)
 * plan-কে নিজেই একটা Charge-এর মতো ব্যবহার করা যায়: calculate(quantity) দিলে
 * প্রতিটা লাইনকে ওই quantity দিয়ে হিসাব করে যোগ করে। আসল হিসাব price()-এ, যেখানে প্রতিটা
 * metric-এর আলাদা usage দেওয়া যায়।
 */
public record Plan(String code, int version, String currency, List<PlanLine> lines) implements Charge {

    public Plan {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("plan code is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be 1 or more");
        }
        if (currency == null || !(currency.equals("BDT") || currency.equals("USD"))) {
            throw new IllegalArgumentException("currency must be BDT or USD");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a plan needs at least one line");
        }
        lines = List.copyOf(lines);
    }

    @Override
    public String type() {
        return "PLAN";
    }

    /** usageByMetric: metric code থেকে usage-এর পরিমাণ। না থাকা metric শূন্য ধরা হয়। */
    public PlanPrice price(Map<String, BigDecimal> usageByMetric) {
        List<PlanPrice.Line> priced = lines.stream().map(line -> {
            BigDecimal usage = line.metricCode() == null
                    ? BigDecimal.ONE
                    : usageByMetric.getOrDefault(line.metricCode(), BigDecimal.ZERO);
            ChargeResult r = line.price(usage);
            return new PlanPrice.Line(line.description(), line.metricCode(), r.quantity(), r.rule(), r.amount());
        }).toList();

        BigDecimal total = priced.stream()
                .map(PlanPrice.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PlanPrice(code, currency, priced, total);
    }

    /** Charge হিসেবে ব্যবহার: সব লাইনকে একই quantity দেওয়া হয়। */
    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        BigDecimal total = BigDecimal.ZERO;
        StringBuilder rule = new StringBuilder("plan " + code + ": ");
        boolean first = true;
        for (PlanLine line : lines) {
            ChargeResult r = line.charge().calculate(line.metricCode() == null ? BigDecimal.ONE : quantity);
            total = total.add(r.amount());
            if (!first) {
                rule.append(" + ");
            }
            first = false;
            rule.append(line.description()).append(" ").append(r.amount().toPlainString());
        }
        return new ChargeResult(quantity, total, rule.toString());
    }
}