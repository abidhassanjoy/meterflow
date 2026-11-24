package com.meterflow.invoicing;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

@RestController
class InvoiceController {

    private final InvoiceService service;

    InvoiceController(InvoiceService service) {
        this.service = service;
    }

    /** asOf না দিলে আজ (UTC)। period শেষ না হলে 409। */
    @PostMapping("/invoices/generate")
    ResponseEntity<Object> generate(@RequestBody InvoiceService.GenerateRequest request) {
        if (request == null || request.subscriptionId() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "subscriptionId is required"));
        }
        LocalDate asOf = request.asOf() == null ? LocalDate.now(ZoneOffset.UTC) : request.asOf();
        Optional<Invoice> invoice = service.generate(request.subscriptionId(), asOf);
        if (invoice.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "nothing to bill: the current period has not finished"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(invoice.get());
    }

    /** একটা invoice-এর প্রতিটা line-এ usage, event সংখ্যা আর দামের নিয়ম একসাথে। */
    @GetMapping("/invoices/{id}")
    Invoice get(@PathVariable("id") long id) {
        return service.get(id);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> billingFailed(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "billing failed: " + e.getMessage()));
    }
}