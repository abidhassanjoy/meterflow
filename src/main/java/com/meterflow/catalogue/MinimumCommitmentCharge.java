package com.meterflow.catalogue;

import java.math.BigDecimal;

import static com.meterflow.catalogue.ChargeSupport.*;

/**
 * হিসাব যত-ই হোক, অন্তত minimum টাকা দিতে হবে। হিসাব বেশি হলে হিসাবের টাকাই।
 */
public record MinimumCommitmentCharge(BigDecimal minimum, Charge inner) implements Charge {

    public MinimumCommitmentCharge {
        checkPrice(minimum, "minimum");
        if (inner == null) {
            throw new IllegalArgumentException("inner charge is required");
        }
    }

    @Override
    public String type() {
        return "MINIMUM_COMMITMENT";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        ChargeResult innerResult = inner.calculate(quantity);
        BigDecimal floor = round(minimum);
        if (innerResult.amount().compareTo(floor) >= 0) {
            return innerResult;   // হিসাব ন্যূনতমের চেয়ে বেশি, তাই কিছু বদলায় না
        }
        return new ChargeResult(innerResult.quantity(), floor,
                "minimum commitment " + plain(minimum) + " (usage came to "
                        + plain(innerResult.amount()) + ")");
    }
}