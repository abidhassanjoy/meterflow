package com.meterflow.catalogue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** QA4: নতুন charge ধরন যোগ করতে কোনো পুরনো pricing class বদলাতে হয় না। */
@SpringBootTest
@Import(NewChargeTypeTest.Extra.class)
class NewChargeTypeTest {

    /** একদম নতুন একটা charge: প্রতি ইউনিটের দাম, কিন্তু মোট একটা সীমার বেশি নয়। */
    record Capped(BigDecimal unitPrice, BigDecimal cap) implements Charge {
        @Override
        public String type() {
            return "CAPPED_PER_UNIT";
        }

        @Override
        public ChargeResult calculate(BigDecimal quantity) {
            BigDecimal amount = quantity.multiply(unitPrice).min(cap).setScale(2, RoundingMode.HALF_UP);
            return new ChargeResult(quantity, amount, quantity.toPlainString() + " units x "
                    + unitPrice.toPlainString() + ", capped at " + cap.toPlainString());
        }
    }

    @TestConfiguration
    static class Extra {
        @Bean
        ChargeType cappedPerUnit() {
            return ChargeType.of("CAPPED_PER_UNIT", (c, r) -> new Capped(
                    ChargeRegistry.number(c, "unitPrice"), ChargeRegistry.number(c, "cap")));
        }
    }

    @Autowired PlanService plans;
    @Autowired ChargeRegistry registry;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aNewChargeTypeWorksWithoutEditingExistingCode() {
        assertEquals(true, registry.types().contains("CAPPED_PER_UNIT"));
        String code = "plan_" + UUID.randomUUID();
        try {
            plans.create(new PlanService.PlanSpec(code, "Capped", null, "BDT", List.of(
                    new PlanService.LineSpec("API calls", "api_calls",
                            Map.of("type", "CAPPED_PER_UNIT", "unitPrice", "0.5", "cap", "100")))));

            PlanPrice small = plans.price(code, null, Map.of("api_calls", new BigDecimal("50")));
            PlanPrice big = plans.price(code, null, Map.of("api_calls", new BigDecimal("1000")));

            assertEquals(0, new BigDecimal("25.00").compareTo(small.total()));
            assertEquals(0, new BigDecimal("100.00").compareTo(big.total()));
        } finally {
            jdbc.update("DELETE FROM plan_charge WHERE plan_id IN (SELECT id FROM plan WHERE code = ?)", code);
            jdbc.update("DELETE FROM plan WHERE code = ?", code);
        }
    }
}