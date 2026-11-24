package com.meterflow.catalogue;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Spring নিজে সব ChargeType bean খুঁজে এনে এখানে দেয়। কনফিগের "type" দেখে সঠিকটা বেছে Charge বানায়। */
@Component
public class ChargeRegistry {

    private final Map<String, ChargeType> byType = new TreeMap<>();

    ChargeRegistry(List<ChargeType> types) {
        for (ChargeType t : types) {
            String key = t.type().toUpperCase(Locale.ROOT);
            if (byType.put(key, t) != null) {
                throw new IllegalStateException("two charge types are named " + key);
            }
        }
    }

    public Charge create(Map<String, Object> config) {
        if (config == null) {
            throw new IllegalArgumentException("charge config is required");
        }
        String type = text(config, "type").toUpperCase(Locale.ROOT);
        ChargeType chargeType = byType.get(type);
        if (chargeType == null) {
            throw new IllegalArgumentException("unknown charge type: " + type + " (known: " + byType.keySet() + ")");
        }
        return chargeType.create(config, this);
    }

    /** "inner" ঘরের ভেতরের charge (free allowance বা minimum commitment-এর জন্য)। */
    public Charge inner(Map<String, Object> config) {
        Object inner = config.get("inner");
        if (!(inner instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("'inner' charge is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> innerConfig = (Map<String, Object>) inner;
        return create(innerConfig);
    }

    public Set<String> types() {
        return Set.copyOf(byType.keySet());
    }

    public static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return v.toString();
    }

    /** সংখ্যা "0.05" লেখা বা 0.05 সংখ্যা, দুভাবেই আসতে পারে। */
    public static BigDecimal number(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }
    }

    public static List<PriceTier> tiers(Map<String, Object> config) {
        Object raw = config.get("tiers");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("'tiers' is required");
        }
        List<PriceTier> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("each tier must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) o;
            BigDecimal upTo = t.get("upTo") == null ? null : number(t, "upTo");
            result.add(new PriceTier(upTo, number(t, "unitPrice")));
        }
        return result;
    }
}