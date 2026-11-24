package com.meterflow.catalogue;

import java.math.BigDecimal;

import static com.meterflow.catalogue.ChargeSupport.*;

/** প্রতি ইউনিটে একই দাম। */
public record PerUnitCharge(BigDecimal unitPrice) implements Charge {

    public PerUnitCharge {
        checkPrice(unitPrice, "unit price");
    }

    @Override
    public String type() {
        return "PER_UNIT";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        BigDecimal amount = quantity.multiply(unitPrice);
        return new ChargeResult(quantity, round(amount),
                plain(quantity) + " units x " + plain(unitPrice));
    }
}