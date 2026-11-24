package com.meterflow.invoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record Invoice(
        Long id,
        String customer,
        long subscriptionId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        String currency,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        BigDecimal creditApplied,
        BigDecimal amountDue,
        List<InvoiceLine> lines
) {

    public Invoice {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder: ধাপে ধাপে invoice গড়ে। build()-এ যোগফল নিজে হিসাব হয়, তাই হাতে ভুল হওয়ার সুযোগ নেই।
     * মোট কর = প্রতিটা line-এর কর-এর যোগফল (আর tax() দিয়ে আলাদা বাড়তি কর দিলে সেটাও)।
     */
    public static final class Builder {
        private Long id;
        private String customer;
        private long subscriptionId;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private String currency;
        private BigDecimal extraTax = BigDecimal.ZERO;
        private final List<InvoiceLine> lines = new ArrayList<>();

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder customer(String customer) {
            this.customer = customer;
            return this;
        }

        public Builder subscriptionId(long subscriptionId) {
            this.subscriptionId = subscriptionId;
            return this;
        }

        public Builder period(LocalDate start, LocalDate end) {
            this.periodStart = start;
            this.periodEnd = end;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        /** line-এর কর ছাড়াও বাড়তি কর থাকলে। */
        public Builder tax(BigDecimal tax) {
            this.extraTax = tax;
            return this;
        }

        public Builder line(InvoiceLine line) {
            this.lines.add(line);
            return this;
        }

        public Invoice build() {
            if (customer == null || customer.isBlank()) {
                throw new IllegalArgumentException("customer is required");
            }
            if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
                throw new IllegalArgumentException("a valid period is required");
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("currency is required");
            }
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("an invoice needs at least one line");
            }
            if (extraTax == null || extraTax.signum() < 0) {
                throw new IllegalArgumentException("tax must be zero or more");
            }
            BigDecimal subtotal = lines.stream()
                    .map(InvoiceLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal lineTax = lines.stream()
                    .map(InvoiceLine::taxAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tax = extraTax.add(lineTax);
            BigDecimal total = subtotal.add(tax);
            BigDecimal due = total.signum() > 0 ? total : BigDecimal.ZERO;
            return new Invoice(id, customer, subscriptionId, periodStart, periodEnd, "DRAFT",
                    currency, subtotal, tax, total, BigDecimal.ZERO, due, lines);
        }
    }
}