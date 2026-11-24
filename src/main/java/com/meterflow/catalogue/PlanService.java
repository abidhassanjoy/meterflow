package com.meterflow.catalogue;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class PlanService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final ChargeRegistry chargeRegistry;

    public PlanService(JdbcTemplate jdbc, ChargeRegistry chargeRegistry) {
        this.chargeRegistry = chargeRegistry;
        this.jdbc = jdbc;
    }

    /** একটা plan-এর সংরক্ষিত রূপ: লাইনগুলোর charge সাধারণ Map হিসেবে। */
    public record PlanSpec(String code, String name, Integer version, String currency, List<LineSpec> lines) {
    }

    public record LineSpec(String description, String metric, Map<String, Object> charge) {
    }

    /**
     * নতুন plan বানায়। একই code থাকলে নতুন version হয় (আগেরটা বদলায় না)।
     * এটাই plan version-এর ভিত্তি (FR10)।
     */
    @Transactional
    public PlanSpec create(PlanSpec spec) {
        if (spec == null) {
            throw bad("request body is required");
        }
        if (spec.code() == null || !CODE.matcher(spec.code()).matches()) {
            throw bad("code is required (letters, digits, _ . - only; max 100)");
        }
        if (spec.name() == null || spec.name().isBlank() || spec.name().length() > 200) {
            throw bad("name is required (max 200)");
        }
        if (spec.lines() == null || spec.lines().isEmpty()) {
            throw bad("at least one line is required");
        }
        // সংরক্ষণের আগেই প্রতিটা charge বানিয়ে দেখি, ভুল থাকলে এখানেই ধরা পড়বে
        List<PlanLine> built = new ArrayList<>();
        for (LineSpec l : spec.lines()) {
            if (l == null || l.description() == null || l.description().isBlank()) {
                throw bad("each line needs a description");
            }
            try {
                built.add(new PlanLine(l.description(), blankToNull(l.metric()), chargeRegistry.create(l.charge())));
            } catch (IllegalArgumentException e) {
                throw bad("line '" + l.description() + "': " + e.getMessage());
            }
        }
        try {
            new Plan(spec.code(), 1, spec.currency(), built);    // currency আর বাকি নিয়ম যাচাই
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }

        Integer latest = jdbc.queryForObject("SELECT MAX(version) FROM plan WHERE code = ?", Integer.class, spec.code());
        int version = latest == null ? 1 : latest + 1;

        long planId;
        try {
            planId = jdbc.queryForObject(
                    "INSERT INTO plan (code, name, version, currency) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, spec.code(), spec.name(), version, spec.currency());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "plan version already exists, try again");
        }
        int order = 0;
        for (LineSpec l : spec.lines()) {
            jdbc.update("INSERT INTO plan_charge (plan_id, metric_code, charge_type, config, sort_order) "
                            + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                    planId, blankToNull(l.metric()), String.valueOf(l.charge().get("type")).toUpperCase(),
                    toJson(Map.of("description", l.description(), "charge", l.charge())), order++);
        }
        return load(spec.code(), version).orElseThrow();
    }

    /** database-এ config-এর ভেতরে লাইনের বিবরণও থাকে। */
    private record LineConfig(String description, Map<String, Object> charge) {
    }

    /** version না দিলে সর্বশেষ version। */
    public Optional<PlanSpec> load(String code, Integer version) {
        var rows = jdbc.query(
                "SELECT id, code, name, version, currency FROM plan WHERE code = ? "
                        + (version == null ? "ORDER BY version DESC LIMIT 1" : "AND version = ?"),
                (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getInt("version"), rs.getString("currency")},
                version == null ? new Object[]{code} : new Object[]{code, version});
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] p = rows.get(0);
        long planId = (Long) p[0];
        List<LineSpec> lines = jdbc.query(
                "SELECT metric_code, config FROM plan_charge WHERE plan_id = ? ORDER BY sort_order, id",
                (rs, n) -> {
                    LineConfig c = fromJson(rs.getString("config"));
                    return new LineSpec(c.description(), rs.getString("metric_code"), c.charge());
                },
                planId);
        return Optional.of(new PlanSpec((String) p[1], (String) p[2], (Integer) p[3], (String) p[4], lines));
    }

    /** সংরক্ষিত plan থেকে হিসাব করার মতো Plan object বানায়। */
    public Plan toPlan(PlanSpec spec) {
        List<PlanLine> lines = new ArrayList<>();
        for (LineSpec l : spec.lines()) {
            lines.add(new PlanLine(l.description(), l.metric(), chargeRegistry.create(l.charge())));
        }
        return new Plan(spec.code(), spec.version(), spec.currency(), lines);
    }

    /** usageByMetric দিলে plan-এর দাম বের করে। */
    public PlanPrice price(String code, Integer version, Map<String, BigDecimal> usageByMetric) {
        PlanSpec spec = load(code, version).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown plan: " + code));
        Map<String, BigDecimal> usage = usageByMetric == null ? Map.of() : usageByMetric;
        for (Map.Entry<String, BigDecimal> e : usage.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw bad("usage for '" + e.getKey() + "' must be zero or more");
            }
        }
        try {
            return toPlan(spec).price(usage);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LineConfig fromJson(String json) {
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> charge = (Map<String, Object>) m.get("charge");
            return new LineConfig((String) m.get("description"), charge);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}