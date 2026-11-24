package com.meterflow.metering;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class IngestionService {

    static final int MAX_BATCH = 1000;
    static final int MAX_PROPERTIES = 20;
    static final Duration LATE_WINDOW = Duration.ofHours(72);

    private static final String INSERT_SQL = """
            INSERT INTO usage_event (customer_id, metric, quantity, occurred_at, idempotency_key, late, properties)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public IngestionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** একটা invoice হয়ে যাওয়া period। */
    private record ClosedPeriod(LocalDate start, LocalDate end) {
        Instant closedAt() {  // period_end ওই দিনসহ ধরা হয়েছে, তাই বন্ধ হয় পরের দিনের শুরুতে (UTC)
            return end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        boolean contains(Instant t) {
            LocalDate d = t.atZone(ZoneOffset.UTC).toLocalDate();
            return !d.isBefore(start) && !d.isAfter(end);
        }
    }

    @Transactional
    public BatchResponse ingest(BatchRequest request) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "events must not be empty");
        }
        if (request.events().size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at most " + MAX_BATCH + " events per request");
        }

        List<EventRequest> events = request.events();
        Map<String, Long> customerIds = findCustomerIds(events);
        Map<Long, List<ClosedPeriod>> closed = findClosedPeriods(customerIds.values());
        Instant now = Instant.now();

        List<BatchResponse.Rejection> rejected = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        int lateCount = 0;

        for (int i = 0; i < events.size(); i++) {
            EventRequest e = events.get(i);
            String problem = validate(e);
            if (problem == null && !customerIds.containsKey(e.customer())) {
                problem = "unknown customer: " + e.customer();
            }

            boolean late = false;
            if (problem == null) {
                Long customerId = customerIds.get(e.customer());
                for (ClosedPeriod p : closed.getOrDefault(customerId, List.of())) {
                    if (p.contains(e.timestamp())) {
                        if (now.isAfter(p.closedAt().plus(LATE_WINDOW))) {
                            problem = "too late: period closed more than 72 hours ago";
                        } else {
                            late = true;
                        }
                        break;
                    }
                }
            }

            if (problem != null) {
                rejected.add(new BatchResponse.Rejection(i, problem));
                continue;
            }
            rows.add(new Object[]{
                    customerIds.get(e.customer()), e.metric(), e.quantity(),
                    Timestamp.from(e.timestamp()), e.idempotencyKey(), late,
                    toJson(e.properties())
            });
        }

        int accepted = 0;
        if (!rows.isEmpty()) {
            int[] counts = jdbc.batchUpdate(INSERT_SQL, rows);
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    accepted++;
                    if ((Boolean) rows.get(i)[5]) lateCount++;
                }
            }
        }
        int duplicates = rows.size() - accepted;
        return new BatchResponse(events.size(), accepted, lateCount, duplicates, rejected.size(), rejected);
    }

    private String validate(EventRequest e) {
        if (e == null) return "event is null";
        if (e.customer() == null || e.customer().isBlank()) return "customer is required";
        if (e.metric() == null || e.metric().isBlank()) return "metric is required";
        if (e.metric().length() > 100) return "metric too long (max 100)";
        if (e.quantity() == null) return "quantity is required";
        if (e.quantity().signum() < 0) return "quantity must not be negative";
        if (e.timestamp() == null) return "timestamp is required";
        if (e.idempotencyKey() == null || e.idempotencyKey().isBlank()) return "idempotencyKey is required";
        if (e.idempotencyKey().length() > 200) return "idempotencyKey too long (max 200)";
        if (e.properties() != null) {
            if (e.properties().size() > MAX_PROPERTIES) return "too many properties (max " + MAX_PROPERTIES + ")";
            for (Map.Entry<String, String> p : e.properties().entrySet()) {
                if (p.getKey() == null || p.getKey().isBlank() || p.getKey().length() > 100) {
                    return "invalid property key (1 to 100 characters)";
                }
                if (p.getValue() == null || p.getValue().length() > 200) {
                    return "invalid property value (max 200 characters)";
                }
            }
        }
        return null;
    }

    /** Map-কে JSON লেখায় বদলায়। না থাকলে খালি {} */
    static String toJson(Map<String, String> props) {
        if (props == null || props.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> en : props.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(en.getKey())).append(':').append(quote(en.getValue()));
        }
        return sb.append('}').toString();
    }

    /** JSON-এর জন্য লেখাকে "..." এর ভেতরে নিরাপদে বসায় (উদ্ধৃতিচিহ্ন, \ ইত্যাদি escape করে)। */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private Map<String, Long> findCustomerIds(List<EventRequest> events) {
        Set<String> names = new HashSet<>();
        for (EventRequest e : events) {
            if (e != null && e.customer() != null && !e.customer().isBlank()) {
                names.add(e.customer());
            }
        }
        Map<String, Long> result = new HashMap<>();
        if (names.isEmpty()) return result;
        String ph = String.join(",", Collections.nCopies(names.size(), "?"));
        jdbc.query("SELECT id, external_id FROM customer WHERE external_id IN (" + ph + ")",
                rs -> { result.put(rs.getString("external_id"), rs.getLong("id")); },
                names.toArray());
        return result;
    }

    /** এই customer-দের যেসব period-এর invoice হয়ে গেছে (VOID বাদে), একবারেই এনে দেয়। */
    private Map<Long, List<ClosedPeriod>> findClosedPeriods(Collection<Long> customerIds) {
        Map<Long, List<ClosedPeriod>> result = new HashMap<>();
        if (customerIds.isEmpty()) return result;
        String ph = String.join(",", Collections.nCopies(customerIds.size(), "?"));
        jdbc.query("SELECT customer_id, period_start, period_end FROM invoice "
                        + "WHERE status <> 'VOID' AND customer_id IN (" + ph + ")",
                rs -> {
                    result.computeIfAbsent(rs.getLong("customer_id"), k -> new ArrayList<>())
                            .add(new ClosedPeriod(rs.getDate("period_start").toLocalDate(),
                                    rs.getDate("period_end").toLocalDate()));
                },
                customerIds.toArray());
        return result;
    }
}