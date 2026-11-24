package com.meterflow.invoicing;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * credit note: finalised invoice-এর বিবাদে সংশোধন। টাকাটা customer-এর credit খাতায় জমা হয়
 * (পরের invoice-এ নিজে থেকে কাটা হয়)। একটা invoice-এর সব note-এর যোগফল তার মোটের বেশি হতে পারে না।
 */
@Service
public class CreditNoteService {

    public record CreateRequest(BigDecimal amount, String reason) {
    }

    public record Note(Long id, long invoiceId, String customer, BigDecimal amount, String reason,
                       Instant createdAt) {
    }

    private record InvoiceRow(long customerId, String status, BigDecimal total) {
    }

    private final JdbcTemplate jdbc;

    public CreditNoteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Note create(long invoiceId, CreateRequest r) {
        if (r == null) {
            throw bad("request body is required");
        }
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw bad("amount must be more than zero");
        }
        if (r.amount().stripTrailingZeros().scale() > 2) {
            throw bad("amount can have at most 2 decimal places");
        }
        if (r.reason() == null || r.reason().isBlank()) {
            throw bad("reason is required");
        }
        String reason = r.reason().trim();
        if (reason.length() > 200) {
            throw bad("reason is too long (max 200)");
        }

        // invoice-এর সারি তালাবদ্ধ: দুটো note একসাথে এলে একটা অন্যটার শেষ হওয়া পর্যন্ত অপেক্ষা করে
        List<InvoiceRow> rows = jdbc.query(
                "SELECT customer_id, status, total FROM invoice WHERE id = ? FOR UPDATE",
                (rs, n) -> new InvoiceRow(rs.getLong("customer_id"), rs.getString("status"),
                        rs.getBigDecimal("total")),
                invoiceId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown invoice: " + invoiceId);
        }
        InvoiceRow invoice = rows.get(0);
        if (!invoice.status().equals("FINALISED") && !invoice.status().equals("PAID")) {
            throw conflict("only finalised invoices can be credited (this one is " + invoice.status() + ")");
        }
        if (invoice.total().signum() <= 0) {
            throw conflict("this invoice has no amount to credit");
        }

        BigDecimal already = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM credit_note WHERE invoice_id = ?",
                BigDecimal.class, invoiceId);
        BigDecimal remaining = invoice.total().subtract(already);
        if (r.amount().compareTo(remaining) > 0) {
            throw conflict("credit notes would exceed the invoice total (remaining "
                    + remaining.toPlainString() + ")");
        }

        long id = jdbc.queryForObject(
                "INSERT INTO credit_note (invoice_id, customer_id, amount, reason) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, invoiceId, invoice.customerId(), r.amount(), reason);
        jdbc.update("INSERT INTO credit_ledger (customer_id, amount, reason, invoice_id) VALUES (?, ?, ?, ?)",
                invoice.customerId(), r.amount(),
                "credit note " + id + " for invoice " + invoiceId + ": " + reason, invoiceId);

        return jdbc.query(NOTE_SQL + " WHERE n.id = ?", (rs, n) -> map(rs), id).get(0);
    }

    public List<Note> list(long invoiceId) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM invoice WHERE id = ?", Integer.class, invoiceId);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown invoice: " + invoiceId);
        }
        return jdbc.query(NOTE_SQL + " WHERE n.invoice_id = ? ORDER BY n.id", (rs, n) -> map(rs), invoiceId);
    }

    private static final String NOTE_SQL =
            "SELECT n.id, n.invoice_id, c.external_id, n.amount, n.reason, n.created_at "
                    + "FROM credit_note n JOIN customer c ON c.id = n.customer_id";

    private static Note map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Note(rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("external_id"),
                rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}