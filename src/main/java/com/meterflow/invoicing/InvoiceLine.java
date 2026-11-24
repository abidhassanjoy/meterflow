package com.meterflow.invoicing;

import java.math.BigDecimal;

/**
 * invoice-এর একটা line: কত usage, কয়টা event থেকে, কোন নিয়মে কত টাকা, আর কোন কর নিয়মে কত কর।
 * lineType: USAGE (plan-এর হিসাব) অথবা ADJUSTMENT (plan বদলের proration)।
 * taxRule: যেমন "VAT 15%", কর না থাকলে null।
 */
public record InvoiceLine(
        String lineType,
        String description,
        String metricCode,
        BigDecimal quantity,
        long eventCount,
        String priceRule,
        BigDecimal amount,
        String taxRule,
        BigDecimal taxRate,
        BigDecimal taxAmount
) {

    /** কর ছাড়া সরল line। */
    public InvoiceLine(String description, String metricCode, BigDecimal quantity,
                       long eventCount, String priceRule, BigDecimal amount) {
        this("USAGE", description, metricCode, quantity, eventCount, priceRule, amount,
                null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** এই line-এর টাকার উপর কর বসিয়ে নতুন line ফেরত দেয়। */
    public InvoiceLine withTax(TaxRule rule) {
        return new InvoiceLine(lineType, description, metricCode, quantity, eventCount, priceRule, amount,
                rule.code() == null ? null : rule.label(), rule.rate(), rule.taxOn(amount));
    }
}