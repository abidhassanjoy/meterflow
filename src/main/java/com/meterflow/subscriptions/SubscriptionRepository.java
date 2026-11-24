package com.meterflow.subscriptions;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/** subscription টেবিলে পড়া-লেখা। update-এ optimistic locking: version না মিললে লেখা হয় না। */
@Repository
class SubscriptionRepository {

    private static final String COLUMNS =
            "id, customer_id, plan_id, status, current_period_start, current_period_end, "
                    + "trial_ends_at, convert_to_paid, pending_plan_id, billing_anchor_day, version";

    private final JdbcTemplate jdbc;

    SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Subscription insert(Subscription s) {
        long id = jdbc.queryForObject(
                "INSERT INTO subscription (customer_id, plan_id, status, current_period_start, "
                        + "current_period_end, trial_ends_at, convert_to_paid, pending_plan_id, billing_anchor_day) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS date), ?, CAST(? AS bigint), ?) RETURNING id",
                Long.class,
                s.customerId(), s.planId(), s.status().name(), sql(s.periodStart()), sql(s.periodEnd()),
                sql(s.trialEndsAt()), s.convertToPaid(), s.pendingPlanId(), s.anchorDay());
        return find(id).orElseThrow();
    }

    Optional<Subscription> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    /** বাতিল নয় এমন subscription (একজন customer-এর একটাই থাকতে পারে)। */
    Optional<Subscription> findLiveByCustomer(long customerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE customer_id = ? AND status <> 'CANCELLED'",
                this::map, customerId).stream().findFirst();
    }

    /**
     * শুধু তখনই লেখে যখন database-এ version এখনও সেটাই, যেটা পড়ার সময় ছিল।
     * না মিললে অন্য কেউ আগে বদলে ফেলেছে, তাই আমরা কিছু না লিখে ব্যর্থ হই।
     */
    void update(Subscription s) {
        int rows = jdbc.update(
                "UPDATE subscription SET plan_id = ?, status = ?, current_period_start = ?, "
                        + "current_period_end = ?, trial_ends_at = CAST(? AS date), "
                        + "pending_plan_id = CAST(? AS bigint), billing_anchor_day = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                s.planId(), s.status().name(), sql(s.periodStart()), sql(s.periodEnd()),
                sql(s.trialEndsAt()), s.pendingPlanId(), s.anchorDay(), s.id(), s.version());
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                    "subscription " + s.id() + " was changed by someone else");
        }
    }

    private Subscription map(ResultSet rs, int rowNum) throws SQLException {
        Date trial = rs.getDate("trial_ends_at");
        long pending = rs.getLong("pending_plan_id");
        boolean noPending = rs.wasNull();
        return new Subscription(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getLong("plan_id"),
                SubscriptionStatus.valueOf(rs.getString("status")),
                rs.getDate("current_period_start").toLocalDate(),
                rs.getDate("current_period_end").toLocalDate(),
                trial == null ? null : trial.toLocalDate(),
                rs.getBoolean("convert_to_paid"),
                noPending ? null : pending,
                rs.getInt("billing_anchor_day"),
                rs.getLong("version"));
    }

    private static Date sql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}