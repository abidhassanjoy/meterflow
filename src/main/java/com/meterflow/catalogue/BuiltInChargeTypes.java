package com.meterflow.catalogue;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.meterflow.catalogue.ChargeRegistry.number;
import static com.meterflow.catalogue.ChargeRegistry.tiers;

/** আগে থেকে থাকা আটটা charge ধরন। প্রতিটা একটা ছোট bean। */
@Configuration(proxyBeanMethods = false)
class BuiltInChargeTypes {

    @Bean
    ChargeType flat() {
        return ChargeType.of("FLAT", (c, r) -> new FlatCharge(number(c, "fee")));
    }

    @Bean
    ChargeType perUnit() {
        return ChargeType.of("PER_UNIT", (c, r) -> new PerUnitCharge(number(c, "unitPrice")));
    }

    @Bean
    ChargeType graduated() {
        return ChargeType.of("GRADUATED", (c, r) -> new GraduatedCharge(tiers(c)));
    }

    @Bean
    ChargeType volume() {
        return ChargeType.of("VOLUME", (c, r) -> new VolumeCharge(tiers(c)));
    }

    @Bean
    ChargeType packageCharge() {
        return ChargeType.of("PACKAGE",
                (c, r) -> new PackageCharge(number(c, "packageSize"), number(c, "packagePrice")));
    }

    @Bean
    ChargeType perSeat() {
        return ChargeType.of("PER_SEAT", (c, r) -> new PerSeatCharge(number(c, "seatPrice")));
    }

    @Bean
    ChargeType freeAllowance() {
        return ChargeType.of("FREE_ALLOWANCE",
                (c, r) -> new FreeAllowanceCharge(number(c, "freeUnits"), r.inner(c)));
    }

    @Bean
    ChargeType minimumCommitment() {
        return ChargeType.of("MINIMUM_COMMITMENT",
                (c, r) -> new MinimumCommitmentCharge(number(c, "minimum"), r.inner(c)));
    }
}