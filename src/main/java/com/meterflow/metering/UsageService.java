package com.meterflow.metering;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class UsageService {

    private static final long MAX_DAYS = 400;

    private final JdbcTemplate jdbc;
    private final MetricService metrics;
    private final AggregationRegistry registry;

    public UsageService(JdbcTemplate jdbc, MetricService metrics, AggregationRegistry registry) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.registry = registry;
    }

    /** from আর to দুটোই ওই দিনসহ ধরা হয় (UTC অনুযায়ী)। */
    public UsageResult compute(String customer, String metricCode, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from and to are required, and to must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date range too long (max " + MAX_DAYS + " days)");
        }

        List<Long> ids = jdbc.query("SELECT id FROM customer WHERE external_id = ?",
                (rs, n) -> rs.getLong(1), customer);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown customer: " + customer);
        }
        MetricDefinition m = metrics.find(metricCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown metric: " + metricCode));
        AggregationStrategy strategy = registry.find(m.aggregation())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "no strategy for " + m.aggregation()));
        AggregationStrategy.SqlExpr expr = strategy.expression(m.property());

        StringBuilder sql = new StringBuilder("SELECT ").append(expr.sql())
                .append(", COUNT(*) FROM usage_event")
                .append(" WHERE customer_id = ? AND metric = ? AND occurred_at >= ? AND occurred_at < ?");

        List<Object> params = new ArrayList<>(expr.params());   // SELECT-এর ? গুলো আগে
        params.add(ids.get(0));
        params.add(m.eventName() != null ? m.eventName() : m.code());
        params.add(Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
        params.add(Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));

        for (Map.Entry<String, String> f : m.filters().entrySet()) {
            sql.append(" AND properties ->> CAST(? AS text) = CAST(? AS text)");
            params.add(f.getKey());
            params.add(f.getValue());
        }

        return jdbc.queryForObject(sql.toString(),
                (rs, n) -> new UsageResult(customer, m.code(), m.aggregation(), from, to,
                        rs.getBigDecimal(1), rs.getLong(2)),
                params.toArray());
    }
}