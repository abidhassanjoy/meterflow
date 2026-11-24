package com.meterflow.catalogue;

import java.math.BigDecimal;

import static com.meterflow.catalogue.ChargeSupport.*;

/** সিটের সংখ্যা x প্রতি সিটের দাম। quantity হিসেবে সিটের সংখ্যা আসে। */
public record PerSeatCharge(BigDecimal seatPrice) implements Charge {

    public PerSeatCharge {
        checkPrice(seatPrice, "seat price");
    }

    @Override
    public String type() {
        return "PER_SEAT";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        if (quantity.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("seats must be a whole number");
        }
        BigDecimal amount = quantity.multiply(seatPrice);
        return new ChargeResult(quantity, round(amount),
                plain(quantity) + " seats x " + plain(seatPrice));
    }
}