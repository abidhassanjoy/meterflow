package com.meterflow.subscriptions;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * একটা customer-এর একটা plan-এ subscription। অবস্থা বদলের নিয়ম State object-এর হাতে,
 * এই class শুধু সেটা ডাকে আর নিজের তারিখ ও plan ঠিক রাখে।
 * billingAnchorDay: প্রতি মাসের কোন তারিখে নতুন period শুরু (ছোট মাসে মাসের শেষ দিন)।
 */
public final class Subscription {

    private final Long id;
    private final long customerId;
    private long planId;
    private SubscriptionStatus status;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate trialEndsAt;
    private final boolean convertToPaid;
    private Long pendingPlanId;
    private int anchorDay;
    private final long version;

    Subscription(Long id, long customerId, long planId, SubscriptionStatus status,
                 LocalDate periodStart, LocalDate periodEnd, LocalDate trialEndsAt,
                 boolean convertToPaid, Long pendingPlanId, int anchorDay, long version) {
        this.id = id;
        this.customerId = customerId;
        this.planId = planId;
        this.status = status;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.trialEndsAt = trialEndsAt;
        this.convertToPaid = convertToPaid;
        this.pendingPlanId = pendingPlanId;
        this.anchorDay = anchorDay;
        this.version = version;
    }

    /** নতুন subscription। trialDays > 0 হলে TRIAL দিয়ে শুরু, নইলে সরাসরি ACTIVE। */
    public static Subscription start(long customerId, long planId, LocalDate startDate,
                                     int trialDays, boolean convertToPaid) {
        if (startDate == null) {
            throw new IllegalArgumentException("start date is required");
        }
        if (trialDays < 0 || trialDays > 365) {
            throw new IllegalArgumentException("trial days must be between 0 and 365");
        }
        int anchor = startDate.getDayOfMonth();
        boolean trial = trialDays > 0;
        return new Subscription(null, customerId, planId,
                trial ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE,
                startDate, periodEndFor(startDate, anchor),
                trial ? startDate.plusDays(trialDays) : null,
                convertToPaid, null, anchor, 0);
    }

    // ---------- তারিখের হিসাব ----------

    /** পরের period-এর প্রথম দিন। anchorDay ছোট মাসের চেয়ে বড় হলে মাসের শেষ দিন। */
    static LocalDate nextPeriodStart(LocalDate start, int anchorDay) {
        YearMonth next = YearMonth.from(start).plusMonths(1);
        return next.atDay(Math.min(anchorDay, next.lengthOfMonth()));
    }

    /** period-এর শেষ দিন (ওই দিনসহ)। */
    static LocalDate periodEndFor(LocalDate start, int anchorDay) {
        return nextPeriodStart(start, anchorDay).minusDays(1);
    }

    public int periodLengthDays() {
        return (int) ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
    }

    /** today থেকে period-এর শেষ পর্যন্ত কত দিন বাকি (আজ আর শেষ দিন দুটোই ধরে)। proration-এ লাগবে। */
    public int remainingDays(LocalDate today) {
        if (today.isBefore(periodStart)) {
            return periodLengthDays();
        }
        if (today.isAfter(periodEnd)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(today, periodEnd) + 1;
    }

    // ---------- অবস্থা বদল (নিয়ম State-এর হাতে) ----------

    public void pause() {
        status = state().pause();
    }

    public void resume() {
        status = state().resume();
    }

    public void cancel() {
        status = state().cancel();
        pendingPlanId = null;
    }

    public void expireTrial() {
        status = state().expireTrial();
        pendingPlanId = null;
    }

    /** trial শেষ হলে paid চালু: বিলের প্রথম period শুরু হয় trial শেষের দিন থেকে। */
    public void convertTrial() {
        status = state().convertTrial();
        LocalDate start = trialEndsAt != null ? trialEndsAt : periodStart;
        anchorDay = start.getDayOfMonth();
        periodStart = start;
        periodEnd = periodEndFor(start, anchorDay);
        trialEndsAt = null;
    }

    // ---------- plan বদল ----------

    /** এখনই plan বদল। proration-এর হিসাব service করবে। */
    public void changePlanNow(long newPlanId) {
        requirePlanChangeAllowed();
        if (newPlanId == planId) {
            throw new IllegalArgumentException("already on this plan");
        }
        planId = newPlanId;
        pendingPlanId = null;
    }

    /** period শেষে plan বদল। এখনকার plan-এই ফিরলে অপেক্ষমাণ পরিবর্তন বাতিল হয়। */
    public void changePlanAtPeriodEnd(long newPlanId) {
        requirePlanChangeAllowed();
        pendingPlanId = (newPlanId == planId) ? null : newPlanId;
    }

    private void requirePlanChangeAllowed() {
        if (!state().canChangePlan()) {
            throw new IllegalTransitionException("cannot change plan of a " + status + " subscription");
        }
    }

    /** পরের period শুরু: অপেক্ষমাণ plan থাকলে সেটা বসে। billing run এটা ডাকবে। */
    public void renew() {
        if (!state().isBillable()) {
            throw new IllegalTransitionException("cannot renew a " + status + " subscription");
        }
        if (pendingPlanId != null) {
            planId = pendingPlanId;
            pendingPlanId = null;
        }
        periodStart = periodEnd.plusDays(1);
        periodEnd = periodEndFor(periodStart, anchorDay);
    }

    private SubscriptionState state() {
        return SubscriptionStates.of(status);
    }

    // ---------- তথ্য পড়া ----------

    public Long id() {
        return id;
    }

    public long customerId() {
        return customerId;
    }

    public long planId() {
        return planId;
    }

    public SubscriptionStatus status() {
        return status;
    }

    public LocalDate periodStart() {
        return periodStart;
    }

    public LocalDate periodEnd() {
        return periodEnd;
    }

    public LocalDate trialEndsAt() {
        return trialEndsAt;
    }

    public boolean convertToPaid() {
        return convertToPaid;
    }

    public Long pendingPlanId() {
        return pendingPlanId;
    }

    public int anchorDay() {
        return anchorDay;
    }

    public long version() {
        return version;
    }

    public boolean isBillable() {
        return state().isBillable();
    }
}