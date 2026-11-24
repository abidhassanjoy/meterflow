package com.meterflow.subscriptions;

/**
 * একটা অবস্থা কী কী কাজ মানে, সেটা এই interface বলে। (State pattern)
 * ডিফল্টে সব কাজ নিষিদ্ধ। প্রতিটা অবস্থা শুধু নিজের অনুমোদিত কাজ চালু করে।
 * প্রতিটা কাজ নতুন অবস্থা ফেরত দেয়।
 */
interface SubscriptionState {

    SubscriptionStatus status();

    default SubscriptionStatus pause() {
        throw refuse("pause");
    }

    default SubscriptionStatus resume() {
        throw refuse("resume");
    }

    default SubscriptionStatus cancel() {
        throw refuse("cancel");
    }

    default SubscriptionStatus convertTrial() {
        throw refuse("convert trial of");
    }

    default SubscriptionStatus expireTrial() {
        throw refuse("expire trial of");
    }

    /** plan বদলানো চলবে কি না */
    default boolean canChangePlan() {
        return false;
    }

    /** এই অবস্থায় বিল হয় ও period নবায়ন হয় কি না */
    default boolean isBillable() {
        return false;
    }

    private IllegalTransitionException refuse(String action) {
        return new IllegalTransitionException("cannot " + action + " a " + status() + " subscription");
    }
}