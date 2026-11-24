package com.meterflow.invoicing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class InvoiceRepository {

    private final JdbcTemplate jdbc;

    InvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * draft invoice বানায়। একই (customer, period) আগে থাকলে কিছু বানায় না (ফাঁকা ফেরত)।
     * plan-এর code আর version মনে রাখা হয়, যাতে পরে late event এলে একই plan দিয়ে আবার দাম হিসাব করা যায়।
     */
    Optional<Long> insertDraft(long customerId, long subscriptionId, LocalDate start, LocalDate end,
                               String currency, String planCode, int planVersion) {
        List<Long> ids = jdbc.query(
                "INSERT INTO invoice (customer_id, subscription_id, period_start, period_end, status, currency, "
                        + "plan_code, plan_version) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?) "
                        + "ON CONFLICT (customer_id, period_start, period_end) DO NOTHING RETURNING id",
                (rs, n) -> rs.getLong(1),
                customerId, subscriptionId, Date.valueOf(start), Date.valueOf(end), currency,
                planCode, planVersion);
        return ids.stream().findFirst();
    }

    void insertLines(long invoiceId, List<InvoiceLine> lines) {
        List<Object[]> rows = new ArrayList<>();
        for (InvoiceLine l : lines) {
            rows.add(new Object[]{invoiceId, l.lineType(), l.description(), l.metricCode(), l.quantity(),
                    l.eventCount(), l.priceRule(), l.amount(), l.taxRule(), l.taxRate(), l.taxAmount()});
        }
        jdbc.batchUpdate("INSERT INTO invoice_line "
                + "(invoice_id, line_type, description, metric_code, quantity, event_count, price_rule, "
                + "amount, tax_rule, tax_rate, tax_amount) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    void finalise(long invoiceId, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                  BigDecimal creditApplied, BigDecimal amountDue) {
        int rows = jdbc.update("UPDATE invoice SET status = 'FINALISED', subtotal = ?, tax = ?, total = ?, "
                        + "credit_applied = ?, amount_due = ?, finalised_at = now() "
                        + "WHERE id = ? AND status = 'DRAFT'",
                subtotal, tax, total, creditApplied, amountDue, invoiceId);
        if (rows != 1) {
            throw new IllegalStateException("invoice " + invoiceId + " could not be finalised");
        }
    }

    Optional<Invoice> find(long id) {
        List<Invoice> headers = jdbc.query(
                "SELECT i.id, c.external_id, i.subscription_id, i.period_start, i.period_end, i.status, "
                        + "i.currency, i.subtotal, i.tax, i.total, i.credit_applied, i.amount_due "
                        + "FROM invoice i JOIN customer c ON c.id = i.customer_id WHERE i.id = ?",
                (rs, n) -> new Invoice(rs.getLong("id"), rs.getString("external_id"),
                        rs.getLong("subscription_id"), rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate(), rs.getString("status"),
                        rs.getString("currency").trim(), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("total"),
                        rs.getBigDecimal("credit_applied"), rs.getBigDecimal("amount_due"), List.of()),
                id);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        Invoice h = headers.get(0);
        List<InvoiceLine> lines = jdbc.query(
                "SELECT line_type, description, metric_code, quantity, event_count, price_rule, amount, "
                        + "tax_rule, tax_rate, tax_amount FROM invoice_line WHERE invoice_id = ? ORDER BY id",
                (rs, n) -> new InvoiceLine(rs.getString("line_type"), rs.getString("description"),
                        rs.getString("metric_code"), rs.getBigDecimal("quantity"), rs.getLong("event_count"),
                        rs.getString("price_rule"), rs.getBigDecimal("amount"), rs.getString("tax_rule"),
                        rs.getBigDecimal("tax_rate"), rs.getBigDecimal("tax_amount")),
                id);
        return Optional.of(new Invoice(h.id(), h.customer(), h.subscriptionId(), h.periodStart(),
                h.periodEnd(), h.status(), h.currency(), h.subtotal(), h.tax(), h.total(),
                h.creditApplied(), h.amountDue(), lines));
    }
}