package com.meterflow.catalogue;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * একটা charge ধরনের "কারখানা": কনফিগ (JSON-এর মতো Map) থেকে Charge বানায়।
 * নতুন ধরন যোগ করতে শুধু এই interface মানা একটা নতুন Spring bean লিখলেই হয়,
 * কোনো পুরনো class বদলাতে হয় না।
 */
public interface ChargeType {

    /** কনফিগের "type" ঘরে যে নাম লেখা হয়, যেমন "FLAT" */
    String type();

    Charge create(Map<String, Object> config, ChargeRegistry registry);

    static ChargeType of(String type, BiFunction<Map<String, Object>, ChargeRegistry, Charge> factory) {
        return new ChargeType() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Charge create(Map<String, Object> config, ChargeRegistry registry) {
                return factory.apply(config, registry);
            }
        };
    }
}