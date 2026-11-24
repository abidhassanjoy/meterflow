package com.meterflow.catalogue;

import java.math.BigDecimal;
import java.util.List;

import static com.meterflow.catalogue.ChargeSupport.*;

/** মোট ইউনিট যে ধাপে পড়ে, সব ইউনিট ওই ধাপের দামে। */
public record VolumeCharge(List<PriceTier> tiers) implements Charge {

    public VolumeCharge {
        tiers = checkTiers(tiers);
    }

    @Override
    public String type() {
        return "VOLUME";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        PriceTier chosen = tiers.get(tiers.size() - 1);
        for (PriceTier tier : tiers) {
            if (tier.upTo() == null || quantity.compareTo(tier.upTo()) <= 0) {
                chosen = tier;
                break;
            }
        }
        BigDecimal amount = quantity.multiply(chosen.unitPrice());
        return new ChargeResult(quantity, round(amount),
                "volume tier: " + plain(quantity) + " units, all at " + plain(chosen.unitPrice()));
    }
}