package com.meterflow.invoicing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** প্রতি মিনিটে budget আছে এমন সব customer-এর live spend নতুন করে হিসাব করে alert দেখে। */
@Component
class BudgetScheduler {

    private final LiveSpendService service;

    BudgetScheduler(LiveSpendService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void run() {
        service.evaluateAll();
    }
}