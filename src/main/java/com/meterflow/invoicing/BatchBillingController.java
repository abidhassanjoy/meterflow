package com.meterflow.invoicing;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
class BatchBillingController {

    private final BatchBillingService service;

    BatchBillingController(BatchBillingService service) {
        this.service = service;
    }

    /** body না দিলে আজ (UTC), নতুন run। */
    @PostMapping("/billing-runs/batch")
    BatchBillingService.Result run(@RequestBody(required = false) BatchBillingService.Request request) {
        LocalDate asOf = request == null || request.asOf() == null
                ? LocalDate.now(ZoneOffset.UTC) : request.asOf();
        String runKey = request == null ? null : request.runKey();
        String prefix = request == null ? null : request.customerPrefix();
        return service.run(asOf, runKey, prefix);
    }
}