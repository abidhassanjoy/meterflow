package com.meterflow.subscriptions;

import java.util.EnumMap;
import java.util.Map;

/** চারটা অবস্থা আর কোন অবস্থার জন্য কোন class, তার তালিকা। */
final class SubscriptionStates {

    private static final Map<SubscriptionStatus, SubscriptionState> ALL = new EnumMap<>(SubscriptionStatus.class);

    static {
        for (SubscriptionState s : new SubscriptionState[]{new Trial(), new Active(), new Paused(), new Cancelled()}) {
            ALL.put(s.status(), s);
        }
    }

    private SubscriptionStates() {
    }

    static SubscriptionState of(SubscriptionStatus status) {
        SubscriptionState state = ALL.get(status);
        if (state == null) {
            throw new IllegalArgumentException("unknown status: " + status);
        }
        return state;
    }

    /** পরীক্ষামূলক সময়: চালু বা মেয়াদ শেষ করা যায়, বাতিলও করা যায়। বিল হয় না। */
    private static final class Trial implements SubscriptionState {
        @Override
        public SubscriptionStatus status() {
            return SubscriptionStatus.TRIAL;
        }

        @Override
        public SubscriptionStatus convertTrial() {
            return SubscriptionStatus.ACTIVE;
        }

        @Override
        public SubscriptionStatus expireTrial() {
            return SubscriptionStatus.CANCELLED;
        }

        @Override
        public SubscriptionStatus cancel() {
            return SubscriptionStatus.CANCELLED;
        }

        @Override
        public boolean canChangePlan() {
            return true;
        }
    }

    /** চালু: বিরতি, বাতিল, plan বদল সবই চলে। */
    private static final class Active implements SubscriptionState {
        @Override
        public SubscriptionStatus status() {
            return SubscriptionStatus.ACTIVE;
        }

        @Override
        public SubscriptionStatus pause() {
            return SubscriptionStatus.PAUSED;
        }

        @Override
        public SubscriptionStatus cancel() {
            return SubscriptionStatus.CANCELLED;
        }

        @Override
        public boolean canChangePlan() {
            return true;
        }

        @Override
        public boolean isBillable() {
            return true;
        }
    }

    /** বিরতিতে: আবার চালু বা বাতিল করা যায়। */
    private static final class Paused implements SubscriptionState {
        @Override
        public SubscriptionStatus status() {
            return SubscriptionStatus.PAUSED;
        }

        @Override
        public SubscriptionStatus resume() {
            return SubscriptionStatus.ACTIVE;
        }

        @Override
        public SubscriptionStatus cancel() {
            return SubscriptionStatus.CANCELLED;
        }
    }

    /** বাতিল: আর কিছুই চলে না। */
    private static final class Cancelled implements SubscriptionState {
        @Override
        public SubscriptionStatus status() {
            return SubscriptionStatus.CANCELLED;
        }
    }
}