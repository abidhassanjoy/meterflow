package com.meterflow.metering;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@RestController
class MetricController {

    private final MetricService metrics;
    private final UsageService usage;

    MetricController(MetricService metrics, UsageService usage) {
        this.metrics = metrics;
        this.usage = usage;
    }

    @PostMapping("/metrics")
    @ResponseStatus(HttpStatus.CREATED)
    MetricDefinition create(@RequestBody MetricDefinition request) {
        return metrics.create(request);   // request-এর id ঘর উপেক্ষা করা হয়
    }

    @GetMapping("/metrics/{code}")
    MetricDefinition get(@PathVariable("code") String code) {
        return metrics.find(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown metric: " + code));
    }

    @GetMapping("/usage")
    UsageResult usage(@RequestParam("customer") String customer,
                      @RequestParam("metric") String metric,
                      @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return usage.compute(customer, metric, from, to);
    }
}