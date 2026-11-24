package com.meterflow.invoicing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
class LiveSpendController {

    record BudgetRequest(BigDecimal amount) {
    }

    private final LiveSpendService service;

    LiveSpendController(LiveSpendService service) {
        this.service = service;
    }

    @GetMapping("/customers/{customer}/spend")
    LiveSpendService.Spend spend(@PathVariable("customer") String customer) {
        return service.estimate(customer);
    }

    @PutMapping("/customers/{customer}/budget")
    LiveSpendService.BudgetView setBudget(@PathVariable("customer") String customer,
                                          @RequestBody BudgetRequest request) {
        return service.setBudget(customer, request == null ? null : request.amount());
    }

    @GetMapping("/customers/{customer}/budget")
    LiveSpendService.BudgetView budget(@PathVariable("customer") String customer) {
        return service.getBudget(customer);
    }

    @GetMapping("/customers/{customer}/alerts")
    List<LiveSpendService.Alert> alerts(@PathVariable("customer") String customer) {
        return service.alerts(customer);
    }
}