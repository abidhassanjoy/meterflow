package com.meterflow.invoicing;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * prepaid credit। প্রতিটা জমা আর খরচ খাতায় (credit_ledger) আলাদা সারি, তাই কোন টাকা কোথায় গেল সবসময় বলা যায়।
 * balance = খাতার যোগফল।
 */
@Service
public class CreditService {

    private static final BigDecimal MAX_TOP_UP = new BigDecimal("1000000000");

    public record TopUpRequest(String customer, BigDecimal amount, String reason) {
    }

    public record Balance(String customer, BigDecimal balance) {
    }

    private final JdbcTemplate jdbc;

    public CreditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Balance topUp(TopUpRequest r) {
        if (r == null || r.customer() == null || r.customer().isBlank()) {
            throw bad("customer is required");
        }
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw bad("amount must be more than zero");
        }
        if (r.amount().stripTrailingZeros().scale() > 2) {
            throw bad("amount can have at most 2 decimal places");
        }
        if (r.amount().compareTo(MAX_TOP_UP) > 0) {
            throw bad("amount is too large");
        }
        String reason = (r.reason() == null || r.reason().isBlank()) ? "prepaid top-up" : r.reason().trim();
        if (reason.length() > 300) {
            throw bad("reason is too long (max 300)");
        }

        List<Long> ids = jdbc.query("SELECT id FROM customer WHERE external_id = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), r.customer());
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown customer: " + r.customer());
        }
        long customerId = ids.get(0);
        jdbc.update("INSERT INTO credit_ledger (customer_id, amount, reason) VALUES (?, ?, ?)",
                customerId, r.amount(), reason);
        return new Balance(r.customer(), balance(customerId));
    }

    public Balance balanceOf(String customer) {
        List<Long> ids = jdbc.query("SELECT id FROM customer WHERE external_id = ?",
                (rs, n) -> rs.getLong(1), customer);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown customer: " + customer);
        }
        return new Balance(customer, balance(ids.get(0)));
    }

    BigDecimal balance(long customerId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM credit_ledger WHERE customer_id = ?",
                BigDecimal.class, customerId);
    }

    /**
     * invoice finalise হওয়ার সময় ডাকা হয় (একই transaction-এ)। customer-এর সারি তালাবদ্ধ, তাই
     * একসাথে দুটো invoice একই credit দুবার খরচ করতে পারে না।
     * মোট ধনাত্মক হলে যতটা সম্ভব credit কাটে, আর কতটা কাটলো সেটা ফেরত দেয়।
     * মোট ঋণাত্মক (credit note-এর মতো) হলে সেই টাকা credit হিসেবে জমা করে।
     */
    public BigDecimal settle(long customerId, long invoiceId, BigDecimal total) {
        jdbc.query("SELECT id FROM customer WHERE id = ? FOR UPDATE", (rs, n) -> rs.getLong(1), customerId);

        if (total.signum() < 0) {
            jdbc.update("INSERT INTO credit_ledger (customer_id, amount, reason, invoice_id) VALUES (?, ?, ?, ?)",
                    customerId, total.negate(), "credit from negative invoice " + invoiceId, invoiceId);
            return BigDecimal.ZERO;
        }
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal balance = balance(customerId);
        if (balance.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal use = balance.min(total);
        jdbc.update("INSERT INTO credit_ledger (customer_id, amount, reason, invoice_id) VALUES (?, ?, ?, ?)",
                customerId, use.negate(), "used on invoice " + invoiceId, invoiceId);
        return use;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}