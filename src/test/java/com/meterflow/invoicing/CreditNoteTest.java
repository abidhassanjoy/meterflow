package com.meterflow.invoicing;

import com.meterflow.catalogue.PlanService;
import com.meterflow.subscriptions.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CreditNoteTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    static final LocalDate JAN_1 = LocalDate.of(2027, 1, 1);

    @Autowired InvoiceService invoices;
    @Autowired SubscriptionService subscriptions;
    @Autowired PlanService plans;
    @Autowired CreditService credits;
    @Autowired CreditNoteService creditNotes;
    @Autowired JdbcTemplate jdbc;

    String customer;
    String planCode;
    long customerId;
    long subscriptionId;
    long invoiceId;

    @BeforeEach
    void setUp() {
        customer = "cn_" + UUID.randomUUID();
        planCode = "plan_" + UUID.randomUUID();
        jdbc.update("INSERT INTO customer (external_id, name, currency) VALUES (?, 'Test', 'BDT')", customer);
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?", Long.class, customer);
        plans.create(new PlanService.PlanSpec(planCode, "Test", null, "BDT", List.of(
                new PlanService.LineSpec("Platform fee", null, Map.of("type", "FLAT", "fee", "500")))));
        subscriptionId = subscriptions.start(
                new SubscriptionService.StartRequest(customer, planCode, null, NOV_1, 0, true)).id();
        Invoice invoice = invoices.generate(subscriptionId, DEC_1).orElseThrow();
        assertAmount("500.00", invoice.total());
        invoiceId = invoice.id();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM credit_note WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM credit_ledger WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM invoice WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM subscription WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", planCode);
        jdbc.update("DELETE FROM plan WHERE code = ?", planCode);
    }

    private static CreditNoteService.CreateRequest req(String amount, String reason) {
        return new CreditNoteService.CreateRequest(new BigDecimal(amount), reason);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "amount was " + actual);
    }

    @Test
    void creditNoteAddsToTheCustomersCredit() {
        CreditNoteService.Note note = creditNotes.create(invoiceId, req("100", "service outage"));

        assertEquals(invoiceId, note.invoiceId());
        assertEquals(customer, note.customer());
        assertAmount("100.00", note.amount());
        assertEquals("service outage", note.reason());
        assertAmount("100", credits.balanceOf(customer).balance());
        assertEquals(1, creditNotes.list(invoiceId).size());
    }

    @Test
    void creditIsUsedOnTheNextInvoice() {
        creditNotes.create(invoiceId, req("100", "billing dispute"));

        Invoice next = invoices.generate(subscriptionId, JAN_1).orElseThrow();

        assertAmount("500.00", next.total());
        assertAmount("100.00", next.creditApplied());
        assertAmount("400.00", next.amountDue());
        assertAmount("0", credits.balanceOf(customer).balance());
    }

    @Test
    void notesCannotAddUpToMoreThanTheInvoiceTotal() {
        creditNotes.create(invoiceId, req("300", "first"));
        creditNotes.create(invoiceId, req("200", "second"));

        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, req("0.01", "third")));
        assertAmount("500", credits.balanceOf(customer).balance());
        assertEquals(2, creditNotes.list(invoiceId).size());
    }

    @Test
    void oneNoteLargerThanTheTotalIsRefused() {
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, req("500.01", "too much")));
        assertAmount("0", credits.balanceOf(customer).balance());
        assertTrue(creditNotes.list(invoiceId).isEmpty());
    }

    @Test
    void invalidRequestsAreRefused() {
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, null));
        assertThrows(ResponseStatusException.class,
                () -> creditNotes.create(invoiceId, new CreditNoteService.CreateRequest(null, "x")));
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, req("0", "x")));
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, req("-5", "x")));
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, req("1.234", "x")));
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(invoiceId, req("10", "  ")));
        assertAmount("0", credits.balanceOf(customer).balance());
    }

    @Test
    void unknownInvoiceIsNotFound() {
        assertThrows(ResponseStatusException.class, () -> creditNotes.create(999_999_999L, req("10", "x")));
        assertThrows(ResponseStatusException.class, () -> creditNotes.list(999_999_999L));
    }

    @Test
    void draftInvoicesCannotBeCredited() {
        long draftId = jdbc.queryForObject(
                "INSERT INTO invoice (customer_id, subscription_id, period_start, period_end, status, currency, total) "
                        + "VALUES (?, ?, DATE '2025-01-01', DATE '2025-01-31', 'DRAFT', 'BDT', 100) RETURNING id",
                Long.class, customerId, subscriptionId);

        assertThrows(ResponseStatusException.class, () -> creditNotes.create(draftId, req("10", "x")));
    }

    @Test
    void twoRacingNotesCannotOverspendTheInvoice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    creditNotes.create(invoiceId, req("300", "race"));
                    return true;
                } catch (ResponseStatusException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertEquals(1, succeeded);
        assertAmount("300", credits.balanceOf(customer).balance());
        assertEquals(1, creditNotes.list(invoiceId).size());
    }
}