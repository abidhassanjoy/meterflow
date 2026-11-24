package com.meterflow.catalogue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** সব charge-এর সাধারণ যাচাই আর গোল করার নিয়ম এক জায়গায়। */
final class ChargeSupport {

    private ChargeSupport() {
    }

    static BigDecimal checkQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("quantity is required");
        }
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
        return quantity;
    }

    static BigDecimal checkPrice(BigDecimal price, String name) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException(name + " must be zero or more");
        }
        return price;
    }

    /** টাকা ২ ঘর দশমিকে (পয়সা), ৫ বা তার বেশি হলে উপরে। */
    static BigDecimal round(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** 1000.00 কে "1000" আর 0.10 কে "0.1" লেখে, rule-এর লেখা পরিষ্কার রাখতে। */
    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** ধাপগুলো ঠিক আছে কি না: ক্রমে বাড়ছে, শুধু শেষটা সীমাহীন, দাম ঋণাত্মক নয়। */
    static List<PriceTier> checkTiers(List<PriceTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("at least one tier is required");
        }
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            PriceTier tier = tiers.get(i);
            if (tier == null) {
                throw new IllegalArgumentException("tier must not be null");
            }
            checkPrice(tier.unitPrice(), "unit price");
            boolean last = i == tiers.size() - 1;
            if (last) {
                if (tier.upTo() != null) {
                    throw new IllegalArgumentException("the last tier must have no upper limit");
                }
            } else {
                if (tier.upTo() == null || tier.upTo().compareTo(previous) <= 0) {
                    throw new IllegalArgumentException(
                            "tier limits must increase, and only the last tier may be unlimited");
                }
                previous = tier.upTo();
            }
        }
        return List.copyOf(tiers);
    }
}