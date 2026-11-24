package com.meterflow.invoicing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static InvoiceLine line(String amount) {
        return new InvoiceLine("x", null, BigDecimal.ONE, 0, "rule", bd(amount));
    }

    private static Invoice.Builder base() {
        return Invoice.builder().customer("c1").subscriptionId(1)
                .period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).currency("BDT");
    }

    @Test
    void totalIsTheSumOfLines() {
        Invoice inv = base().line(line("500.00")).line(line("100.00")).build();
        assertEquals(0, bd("600.00").compareTo(inv.subtotal()));
        assertEquals(0, bd("0").compareTo(inv.tax()));
        assertEquals(0, bd("600.00").compareTo(inv.total()));
    }

    @Test
    void taxIsAddedOnTopOfTheSubtotal() {
        Invoice inv = base().line(line("600.00")).tax(bd("90.00")).build();
        assertEquals(0, bd("690.00").compareTo(inv.total()));
    }

    @Test
    void newInvoiceStartsAsDraft() {
        assertEquals("DRAFT", base().line(line("1.00")).build().status());
    }

    @Test
    void invalidInvoicesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> base().build());                          // line নেই
        assertThrows(IllegalArgumentException.class,
                () -> base().line(line("1")).tax(bd("-1")).build());                                  // ঋণাত্মক tax
        assertThrows(IllegalArgumentException.class, () -> Invoice.builder().line(line("1")).build()); // customer নেই
    }
}