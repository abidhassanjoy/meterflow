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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class InvoiceTaxCreditTest {

    static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    static final LocalDate NOV_16 = LocalDate.of(2026, 11, 16);    // period-এর বাকি ১৫ দিন (৩০-এর মধ্যে)
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);
    static final LocalDate JAN_1 = LocalDate.of(2027, 1, 1);

    @Autowired InvoiceService invoices;
    @Autowired SubscriptionService subscriptions;
    @Autowired PlanService plans;
    @Autowired CreditService credits;
    @Autowired JdbcTemplate jdbc;

    final List<String> planCodes = new ArrayList<>();
    String customer;
    String taxCode;
    long customerId;

    @BeforeEach
    void setUp() {
        customer = "tc_" + UUID.randomUUID();
        taxCode = "vat_" + UUID.randomUUID();
        jdbc.update("INSERT INTO tax_rule (code, name, rate) VALUES (?, 'VAT', 0.15)", taxCode);
        jdbc.update("INSERT INTO customer (external_id, name, currency, tax_rule_code) VALUES (?, 'Test', 'BDT', ?)",
                customer, taxCode);
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE external_id = ?", Long.class, customer);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM credit_ledger WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id IN (SELECT id FROM invoice WHERE customer_id = ?)", customerId);
        jdbc.update("DELETE FROM invoice WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM subscription WHERE customer_id = ?", customerId);   // সমন্বয় নিজে মুছে যায়
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        jdbc.update("DELETE FROM tax_rule WHERE code = ?", taxCode);
        for (String code : planCodes) {
            jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", code);
            jdbc.update("DELETE FROM plan WHERE code = ?", code);
        }
    }

    /** প্রতিটা fee-র জন্য একটা নির্দিষ্ট ফি-র line। */
    private String plan(String... fees) {
        String code = "plan_" + UUID.randomUUID();
        planCodes.add(code);
        List<PlanService.LineSpec> lines = new ArrayList<>();
        int i = 1;
        for (String fee : fees) {
            lines.add(new PlanService.LineSpec("Fee " + i++, null, Map.of("type", "FLAT", "fee", fee)));
        }
        plans.create(new PlanService.PlanSpec(code, "Test", null, "BDT", lines));
        return code;
    }

    private long subscribe(String planCode) {
        return subscriptions.start(
                new SubscriptionService.StartRequest(customer, planCode, null, NOV_1, 0, true)).id();
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "amount was " + actual);
    }

    // ---------- কর ----------

    @Test
    void taxIsAppliedPerLineAndShownOnEachLine() {
        long sub = subscribe(plan("500", "100"));

        Invoice inv = invoices.generate(sub, DEC_1).orElseThrow();

        assertEquals(2, inv.lines().size());
        InvoiceLine first = inv.lines().get(0);
        assertEquals("VAT 15%", first.taxRule());
        assertAmount("0.15", first.taxRate());
        assertAmount("75.00", first.taxAmount());
        assertAmount("15.00", inv.lines().get(1).taxAmount());
        assertAmount("600.00", inv.subtotal());
        assertAmount("90.00", inv.tax());
        assertAmount("690.00", inv.total());
        assertAmount("690.00", inv.amountDue());
        assertAmount("0", inv.creditApplied());
    }

    @Test
    void taxIsRoundedPerLineNotOnTheTotal() {
        long sub = subscribe(plan("0.10", "0.10"));

        Invoice inv = invoices.generate(sub, DEC_1).orElseThrow();

        assertAmount("0.02", inv.lines().get(0).taxAmount());     // 0.015 উপরে
        assertAmount("0.02", inv.lines().get(1).taxAmount());
        assertAmount("0.04", inv.tax());                          // মোট ০.২০-এর উপর ০.০৩ নয়, line-এর যোগফল
        assertAmount("0.24", inv.total());
    }

    @Test
    void noTaxRuleMeansNoTax() {
        jdbc.update("UPDATE customer SET tax_rule_code = NULL WHERE id = ?", customerId);
        long sub = subscribe(plan("500"));

        Invoice inv = invoices.generate(sub, DEC_1).orElseThrow();

        assertNull(inv.lines().get(0).taxRule());
        assertAmount("0", inv.tax());
        assertAmount("500.00", inv.total());
    }

    // ---------- proration সমন্বয় ----------

    @Test
    void prorationAdjustmentIsInvoicedOnceWithTax() {
        String basic = plan("500");
        String pro = plan("800");
        long sub = subscribe(basic);
        subscriptions.changePlan(sub, new SubscriptionService.ChangePlanRequest(pro, null, "NOW"), NOV_16);

        Invoice first = invoices.generate(sub, DEC_1).orElseThrow();

        assertEquals(2, first.lines().size());
        assertEquals("USAGE", first.lines().get(0).lineType());
        assertAmount("800.00", first.lines().get(0).amount());
        InvoiceLine adjustment = first.lines().get(1);
        assertEquals("ADJUSTMENT", adjustment.lineType());
        assertAmount("150.00", adjustment.amount());               // (৮০০ - ৫০০) x ১৫/৩০
        assertAmount("22.50", adjustment.taxAmount());
        assertAmount("950.00", first.subtotal());
        assertAmount("142.50", first.tax());
        assertAmount("1092.50", first.total());
        assertTrue(subscriptions.adjustments(sub).get(0).billed());

        Invoice second = invoices.generate(sub, JAN_1).orElseThrow();   // পরের invoice-এ সমন্বয় আর নেই
        assertEquals(1, second.lines().size());
        assertAmount("920.00", second.total());                     // ৮০০ + ১২০ কর
    }

    // ---------- prepaid credit ----------

    @Test
    void prepaidCreditIsUsedBeforeCharging() {
        long sub = subscribe(plan("500"));                          // কর সহ মোট ৫৭৫
        credits.topUp(new CreditService.TopUpRequest(customer, bd("300"), "welcome"));

        Invoice inv = invoices.generate(sub, DEC_1).orElseThrow();

        assertAmount("575.00", inv.total());
        assertAmount("300.00", inv.creditApplied());
        assertAmount("275.00", inv.amountDue());
        assertAmount("0", credits.balanceOf(customer).balance());
        BigDecimal used = jdbc.queryForObject(
                "SELECT amount FROM credit_ledger WHERE customer_id = ? AND invoice_id = ?",
                BigDecimal.class, customerId, inv.id());
        assertAmount("-300.00", used);
    }

    @Test
    void creditLargerThanTheInvoiceKeepsTheRest() {
        long sub = subscribe(plan("500"));
        credits.topUp(new CreditService.TopUpRequest(customer, bd("1000"), null));

        Invoice inv = invoices.generate(sub, DEC_1).orElseThrow();

        assertAmount("575.00", inv.creditApplied());
        assertAmount("0.00", inv.amountDue());
        assertAmount("425.00", credits.balanceOf(customer).balance());
    }

    @Test
    void negativeInvoiceBecomesCreditAndPaysTheNextOne() {
        String big = plan("800");
        String small = plan("100");
        long sub = subscribe(big);
        subscriptions.changePlan(sub, new SubscriptionService.ChangePlanRequest(small, null, "NOW"), NOV_16);

        Invoice first = invoices.generate(sub, DEC_1).orElseThrow();

        // ১০০ + (১০০ - ৮০০) x ১৫/৩০ = ১০০ - ৩৫০ = -২৫০, কর -৩৭.৫০, মোট -২৮৭.৫০
        assertAmount("-250.00", first.subtotal());
        assertAmount("-37.50", first.tax());
        assertAmount("-287.50", first.total());
        assertAmount("0", first.creditApplied());
        assertAmount("0", first.amountDue());
        assertAmount("287.50", credits.balanceOf(customer).balance());

        Invoice second = invoices.generate(sub, JAN_1).orElseThrow();

        assertAmount("115.00", second.total());                     // ১০০ + ১৫ কর
        assertAmount("115.00", second.creditApplied());
        assertAmount("0", second.amountDue());
        assertAmount("172.50", credits.balanceOf(customer).balance());
    }

    @Test
    void invalidTopUpsAreRefused() {
        assertThrows(ResponseStatusException.class,
                () -> credits.topUp(new CreditService.TopUpRequest(customer, bd("0"), "x")));
        assertThrows(ResponseStatusException.class,
                () -> credits.topUp(new CreditService.TopUpRequest(customer, bd("-5"), "x")));
        assertThrows(ResponseStatusException.class,
                () -> credits.topUp(new CreditService.TopUpRequest(customer, bd("1.234"), "x")));
        assertThrows(ResponseStatusException.class,
                () -> credits.topUp(new CreditService.TopUpRequest(null, bd("5"), "x")));
        assertThrows(ResponseStatusException.class,
                () -> credits.topUp(new CreditService.TopUpRequest("nobody_" + UUID.randomUUID(), bd("5"), "x")));
        assertAmount("0", credits.balanceOf(customer).balance());   // কোনোটাই জমা হয়নি

        assertAmount("50.25", credits.topUp(new CreditService.TopUpRequest(customer, bd("50.25"), "ok")).balance());
    }
}