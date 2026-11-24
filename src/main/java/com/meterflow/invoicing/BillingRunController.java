package com.meterflow.invoicing;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
class BillingRunController {

    private final BillingRunService service;

    BillingRunController(BillingRunService service) {
        this.service = service;
    }

    /** body না দিলে আজ (UTC) আর parallelism ৪। */
    @PostMapping("/billing-runs")
    BillingRunService.Result run(@RequestBody(required = false) BillingRunService.Request request) {
        LocalDate asOf = request == null || request.asOf() == null
                ? LocalDate.now(ZoneOffset.UTC) : request.asOf();
        int parallelism = request == null || request.parallelism() == null
                ? BillingRunService.DEFAULT_PARALLELISM : request.parallelism();
        return service.run(asOf, parallelism);
    }
}