package com.meterflow.catalogue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.meterflow.catalogue.ChargeSupport.*;

/** ধাপে ধাপে দাম: প্রতিটা ধাপের ইউনিট শুধু ওই ধাপের দামে। */
public record GraduatedCharge(List<PriceTier> tiers) implements Charge {

    public GraduatedCharge {
        tiers = checkTiers(tiers);
    }

    @Override
    public String type() {
        return "GRADUATED";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        BigDecimal remaining = quantity;
        BigDecimal previousLimit = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        List<String> parts = new ArrayList<>();

        for (PriceTier tier : tiers) {
            if (remaining.signum() == 0) {
                break;
            }
            // এই ধাপে কত ইউনিট পড়ছে
            BigDecimal size = tier.upTo() == null
                    ? remaining
                    : remaining.min(tier.upTo().subtract(previousLimit));
            total = total.add(size.multiply(tier.unitPrice()));
            parts.add(plain(size) + " x " + plain(tier.unitPrice()));
            remaining = remaining.subtract(size);
            if (tier.upTo() != null) {
                previousLimit = tier.upTo();
            }
        }
        String rule = parts.isEmpty()
                ? "graduated tiers: no usage"
                : "graduated tiers: " + String.join(" + ", parts);
        return new ChargeResult(quantity, round(total), rule);
    }
}