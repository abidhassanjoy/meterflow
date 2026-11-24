package com.meterflow.subscriptions;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.function.Consumer;

import static com.meterflow.subscriptions.SubscriptionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 17);

    /** দেওয়া অবস্থায় একটা subscription: plan 100, period ১৭ নভেম্বর থেকে ১৬ ডিসেম্বর। */
    private static Subscription in(SubscriptionStatus status) {
        return new Subscription(1L, 10L, 100L, status, START, LocalDate.of(2026, 12, 16),
                status == TRIAL ? START.plusDays(14) : null, true, null, 17, 0);
    }

    @Test
    void startWithoutTrialIsActive() {
        Subscription s = Subscription.start(10, 100, START, 0, true);
        assertEquals(ACTIVE, s.status());
        assertEquals(START, s.periodStart());
        assertEquals(LocalDate.of(2026, 12, 16), s.periodEnd());
        assertNull(s.trialEndsAt());
    }

    @Test
    void startWithTrialIsInTrial() {
        Subscription s = Subscription.start(10, 100, START, 14, true);
        assertEquals(TRIAL, s.status());
        assertEquals(LocalDate.of(2026, 12, 1), s.trialEndsAt());
    }

    @Test
    void invalidTrialLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Subscription.start(1, 1, START, -1, true));
        assertThrows(IllegalArgumentException.class, () -> Subscription.start(1, 1, START, 366, true));
        assertThrows(IllegalArgumentException.class, () -> Subscription.start(1, 1, null, 0, true));
    }

    @Test
    void monthEndBillingKeepsItsAnchorDay() {
        Subscription s = Subscription.start(1, 1, LocalDate.of(2026, 1, 31), 0, true);
        assertEquals(LocalDate.of(2026, 2, 27), s.periodEnd());

        s.renew();   // ফেব্রুয়ারির শেষ দিন (২৮) থেকে ৩০ মার্চ
        assertEquals(LocalDate.of(2026, 2, 28), s.periodStart());
        assertEquals(LocalDate.of(2026, 3, 30), s.periodEnd());

        s.renew();   // আবার ৩১ মার্চে ফিরে আসে
        assertEquals(LocalDate.of(2026, 3, 31), s.periodStart());
        assertEquals(LocalDate.of(2026, 4, 29), s.periodEnd());
    }

    @Test
    void everyPeriodStartsOnTheAnchorDay() {
        Subscription s = Subscription.start(1, 1, LocalDate.of(2026, 1, 31), 0, true);
        for (int i = 0; i < 36; i++) {
            LocalDate start = s.periodStart();
            int expectedDay = Math.min(31, YearMonth.from(start).lengthOfMonth());
            assertEquals(expectedDay, start.getDayOfMonth(), "period " + i + " starts " + start);
            int length = s.periodLengthDays();
            assertTrue(length >= 28 && length <= 31, "length was " + length);
            s.renew();
        }
    }

    @Test
    void transitionsAreAllowedOnlyWhereTheStatesPermit() {
        assertAllowed(Subscription::pause, Set.of(ACTIVE));
        assertAllowed(Subscription::resume, Set.of(PAUSED));
        assertAllowed(Subscription::cancel, Set.of(TRIAL, ACTIVE, PAUSED));
        assertAllowed(Subscription::convertTrial, Set.of(TRIAL));
        assertAllowed(Subscription::expireTrial, Set.of(TRIAL));
        assertAllowed(Subscription::renew, Set.of(ACTIVE));
    }

    private static void assertAllowed(Consumer<Subscription> action, Set<SubscriptionStatus> allowedFrom) {
        for (SubscriptionStatus status : SubscriptionStatus.values()) {
            Subscription s = in(status);
            if (allowedFrom.contains(status)) {
                assertDoesNotThrow(() -> action.accept(s), "should be allowed from " + status);
            } else {
                assertThrows(IllegalTransitionException.class, () -> action.accept(s),
                        "should be refused from " + status);
            }
        }
    }

    @Test
    void pauseResumeAndCancelMoveBetweenStates() {
        Subscription s = in(ACTIVE);
        s.pause();
        assertEquals(PAUSED, s.status());
        s.resume();
        assertEquals(ACTIVE, s.status());
        s.cancel();
        assertEquals(CANCELLED, s.status());
    }

    @Test
    void convertingATrialStartsThePaidPeriodOnTheTrialEnd() {
        Subscription s = in(TRIAL);      // trial শেষ ১ ডিসেম্বর
        s.convertTrial();
        assertEquals(ACTIVE, s.status());
        assertEquals(LocalDate.of(2026, 12, 1), s.periodStart());
        assertEquals(LocalDate.of(2026, 12, 31), s.periodEnd());
        assertEquals(1, s.anchorDay());
        assertNull(s.trialEndsAt());
    }

    @Test
    void expiringATrialCancelsIt() {
        Subscription s = in(TRIAL);
        s.expireTrial();
        assertEquals(CANCELLED, s.status());
    }

    @Test
    void planChangeAtPeriodEndIsAppliedOnRenewal() {
        Subscription s = in(ACTIVE);
        s.changePlanAtPeriodEnd(200);
        assertEquals(100L, s.planId());                          // এখনও পুরনো plan
        assertEquals(Long.valueOf(200L), s.pendingPlanId());

        s.renew();
        assertEquals(200L, s.planId());                          // নতুন period-এ নতুন plan
        assertNull(s.pendingPlanId());
        assertEquals(LocalDate.of(2026, 12, 17), s.periodStart());
        assertEquals(LocalDate.of(2027, 1, 16), s.periodEnd());
    }

    @Test
    void choosingTheCurrentPlanAgainDropsThePendingChange() {
        Subscription s = in(ACTIVE);
        s.changePlanAtPeriodEnd(200);
        s.changePlanAtPeriodEnd(100);    // এখনকার plan-এ ফেরা মানে পরিবর্তন বাতিল
        assertNull(s.pendingPlanId());
    }

    @Test
    void planChangeNowSwitchesImmediately() {
        Subscription s = in(ACTIVE);
        s.changePlanNow(200);
        assertEquals(200L, s.planId());
        assertNull(s.pendingPlanId());
        assertThrows(IllegalArgumentException.class, () -> s.changePlanNow(200));    // একই plan
    }

    @Test
    void planChangeIsRefusedWhenPausedOrCancelled() {
        for (SubscriptionStatus status : new SubscriptionStatus[]{PAUSED, CANCELLED}) {
            Subscription s = in(status);
            assertThrows(IllegalTransitionException.class, () -> s.changePlanNow(200), status.name());
            assertThrows(IllegalTransitionException.class, () -> s.changePlanAtPeriodEnd(200), status.name());
        }
    }

    @Test
    void planCanBeChangedDuringTrial() {
        Subscription s = in(TRIAL);
        s.changePlanNow(200);
        assertEquals(200L, s.planId());
    }

    @Test
    void cancellingDropsAPendingPlanChange() {
        Subscription s = in(ACTIVE);
        s.changePlanAtPeriodEnd(200);
        s.cancel();
        assertNull(s.pendingPlanId());
    }

    @Test
    void remainingDaysCountsTodayAndTheLastDay() {
        Subscription nov = new Subscription(1L, 10L, 100L, ACTIVE,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, true, null, 1, 0);
        assertEquals(30, nov.periodLengthDays());
        assertEquals(30, nov.remainingDays(LocalDate.of(2026, 11, 1)));
        assertEquals(15, nov.remainingDays(LocalDate.of(2026, 11, 16)));
        assertEquals(1, nov.remainingDays(LocalDate.of(2026, 11, 30)));
        assertEquals(0, nov.remainingDays(LocalDate.of(2026, 12, 1)));
        assertEquals(30, nov.remainingDays(LocalDate.of(2026, 10, 20)));

        Subscription feb = new Subscription(1L, 10L, 100L, ACTIVE,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), null, true, null, 1, 0);
        assertEquals(28, feb.periodLengthDays());
    }

    @Test
    void refusalMessageNamesTheStatus() {
        IllegalTransitionException e =
                assertThrows(IllegalTransitionException.class, () -> in(CANCELLED).resume());
        assertTrue(e.getMessage().contains("CANCELLED"));
        assertTrue(e.getMessage().contains("resume"));
    }
}