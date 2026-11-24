package com.meterflow.catalogue;

import java.math.BigDecimal;

import static com.meterflow.catalogue.ChargeSupport.*;

/** নির্দিষ্ট ফি। usage যাই হোক, টাকা একই। */
public record FlatCharge(BigDecimal fee) implements Charge {

    public FlatCharge {
        checkPrice(fee, "fee");
    }

    @Override
    public String type() {
        return "FLAT";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        return new ChargeResult(BigDecimal.ONE, round(fee), "flat fee " + plain(fee));
    }
}