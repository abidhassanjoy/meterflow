package com.meterflow.subscriptions;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** প্রতিদিন রাত ১২:০৫ (UTC) এ trial শেষ হওয়া subscription গুলো চালু বা বাতিল করে। */
@Component
@EnableScheduling
class TrialScheduler {

    private final SubscriptionService service;

    TrialScheduler(SubscriptionService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    void processDueTrials() {
        service.processTrials(LocalDate.now(ZoneOffset.UTC));
    }
}