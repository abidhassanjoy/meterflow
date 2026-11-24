package com.meterflow.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void addsExactly() {
        Money a = Money.of("0.10", CurrencyCode.BDT);
        Money b = Money.of("0.20", CurrencyCode.BDT);
        assertEquals(Money.of("0.30", CurrencyCode.BDT), a.plus(b));
    }

    @Test
    void roundsToTwoDecimals() {
        Money m = Money.of("1.005", CurrencyCode.USD);
        assertEquals("1.00 USD", m.toString());
    }

    @Test
    void multipliesByQuantity() {
        BigDecimal unitPrice = new BigDecimal("0.0025");
        Money total = Money.of(unitPrice.multiply(BigDecimal.valueOf(1000)), CurrencyCode.USD);
        assertEquals(Money.of("2.50", CurrencyCode.USD), total);
    }

    @Test
    void rejectsMixedCurrencies() {
        Money bdt = Money.of("10", CurrencyCode.BDT);
        Money usd = Money.of("10", CurrencyCode.USD);
        assertThrows(IllegalArgumentException.class, () -> bdt.plus(usd));
    }

    @Test
    void subtractsAndDetectsNegative() {
        Money a = Money.of("5", CurrencyCode.BDT);
        Money b = Money.of("8", CurrencyCode.BDT);
        assertTrue(a.minus(b).isNegative());
    }
}