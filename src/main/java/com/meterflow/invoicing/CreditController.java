package com.meterflow.invoicing;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CreditController {

    private final CreditService credits;

    CreditController(CreditService credits) {
        this.credits = credits;
    }

    @PostMapping("/credits")
    @ResponseStatus(HttpStatus.CREATED)
    CreditService.Balance topUp(@RequestBody CreditService.TopUpRequest request) {
        return credits.topUp(request);
    }

    @GetMapping("/credits/{customer}")
    CreditService.Balance balance(@PathVariable("customer") String customer) {
        return credits.balanceOf(customer);
    }
}