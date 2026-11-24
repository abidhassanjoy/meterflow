package com.meterflow.catalogue;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

@RestController
class PlanController {

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    /** নতুন plan (বা একই code-এর নতুন version) বানায়। */
    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    PlanService.PlanSpec create(@RequestBody PlanService.PlanSpec request) {
        return plans.create(request);
    }

    /** version না দিলে সর্বশেষ version। */
    @GetMapping("/plans/{code}")
    PlanService.PlanSpec get(@PathVariable("code") String code,
                             @RequestParam(value = "version", required = false) Integer version) {
        return plans.load(code, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown plan: " + code));
    }

    /** body-তে metric অনুযায়ী usage পাঠালে plan-এর দাম লাইন ধরে ধরে ফেরত দেয়। */
    @PostMapping("/plans/{code}/price")
    PlanPrice price(@PathVariable("code") String code,
                    @RequestParam(value = "version", required = false) Integer version,
                    @RequestBody Map<String, BigDecimal> usageByMetric) {
        return plans.price(code, version, usageByMetric);
    }
}