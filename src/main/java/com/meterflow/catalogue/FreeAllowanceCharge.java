package com.meterflow.catalogue;

import java.math.BigDecimal;

import static com.meterflow.catalogue.ChargeSupport.*;

/**
 * প্রথম freeUnits ইউনিট বিনামূল্যে। বাকি ইউনিটের দাম ভেতরের charge ঠিক করে।
 * যেমন ১০০০ বিনামূল্যে, তারপর graduated tier: ১৫০০ ইউনিটে ৫০০ ইউনিটের দাম আসে।
 */
public record FreeAllowanceCharge(BigDecimal freeUnits, Charge inner) implements Charge {

    public FreeAllowanceCharge {
        if (freeUnits == null || freeUnits.signum() < 0) {
            throw new IllegalArgumentException("free units must be zero or more");
        }
        if (inner == null) {
            throw new IllegalArgumentException("inner charge is required");
        }
    }

    @Override
    public String type() {
        return "FREE_ALLOWANCE";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        BigDecimal billable = quantity.subtract(freeUnits).max(BigDecimal.ZERO);
        ChargeResult innerResult = inner.calculate(billable);
        return new ChargeResult(quantity, innerResult.amount(),
                plain(freeUnits) + " free units, then " + plain(billable) + " billable: " + innerResult.rule());
    }
}