package com.meterflow.metering;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class MetricService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private final JdbcTemplate jdbc;
    private final AggregationRegistry registry;

    public MetricService(JdbcTemplate jdbc, AggregationRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Transactional
    public MetricDefinition create(MetricDefinition req) {
        if (req == null) {
            throw bad("request body is required");
        }
        if (req.code() == null || !CODE.matcher(req.code()).matches()) {
            throw bad("code is required (letters, digits, _ . - only; max 100)");
        }
        if (req.name() == null || req.name().isBlank() || req.name().length() > 200) {
            throw bad("name is required (max 200)");
        }
        AggregationStrategy strategy = registry.find(req.aggregation())
                .orElseThrow(() -> bad("unknown aggregation; use one of " + registry.types()));
        if (strategy.needsProperty() && (req.property() == null || req.property().isBlank())) {
            throw bad(strategy.type() + " needs a property");
        }
        if (req.property() != null && req.property().length() > 100) {
            throw bad("property too long (max 100)");
        }
        if (req.eventName() != null && req.eventName().length() > 100) {
            throw bad("eventName too long (max 100)");
        }

        Map<String, String> filters = req.filters() == null ? Map.of() : req.filters();
        if (filters.size() > 10) {
            throw bad("too many filters (max 10)");
        }
        for (Map.Entry<String, String> f : filters.entrySet()) {
            if (f.getKey() == null || f.getKey().isBlank() || f.getKey().length() > 100) {
                throw bad("invalid filter key (1 to 100 characters)");
            }
            if (f.getValue() == null || f.getValue().length() > 200) {
                throw bad("invalid filter value (max 200 characters)");
            }
        }

        long id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO billable_metric (code, name, aggregation, property, event_name) "
                            + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    req.code(), req.name(), strategy.type(),
                    blankToNull(req.property()), blankToNull(req.eventName()));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "metric already exists: " + req.code());
        }
        for (Map.Entry<String, String> f : filters.entrySet()) {
            jdbc.update("INSERT INTO billable_metric_filter (metric_id, property_key, property_value) "
                    + "VALUES (?, ?, ?)", id, f.getKey(), f.getValue());
        }
        return find(req.code()).orElseThrow();
    }

    public Optional<MetricDefinition> find(String code) {
        var rows = jdbc.query(
                "SELECT id, code, name, aggregation, property, event_name FROM billable_metric WHERE code = ?",
                (rs, n) -> new MetricDefinition(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("aggregation"), rs.getString("property"), rs.getString("event_name"),
                        Map.of()),
                code);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        MetricDefinition m = rows.get(0);
        Map<String, String> filters = new LinkedHashMap<>();
        jdbc.query("SELECT property_key, property_value FROM billable_metric_filter WHERE metric_id = ? ORDER BY id",
                (rs, n) -> {
                    filters.put(rs.getString(1), rs.getString(2));
                    return null;
                },
                m.id());
        return Optional.of(new MetricDefinition(m.id(), m.code(), m.name(), m.aggregation(),
                m.property(), m.eventName(), filters));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}