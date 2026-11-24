package com.meterflow.subscriptions;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@RestController
class SubscriptionController {

    private final SubscriptionService service;

    SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    SubscriptionService.View start(@RequestBody SubscriptionService.StartRequest request) {
        return service.start(request);
    }

    @GetMapping("/subscriptions/{id}")
    SubscriptionService.View get(@PathVariable("id") long id) {
        return service.get(id);
    }

    @GetMapping("/subscriptions/{id}/adjustments")
    List<SubscriptionService.Adjustment> adjustments(@PathVariable("id") long id) {
        return service.adjustments(id);
    }

    @PostMapping("/subscriptions/{id}/pause")
    SubscriptionService.View pause(@PathVariable("id") long id) {
        return service.pause(id);
    }

    @PostMapping("/subscriptions/{id}/resume")
    SubscriptionService.View resume(@PathVariable("id") long id) {
        return service.resume(id);
    }

    @PostMapping("/subscriptions/{id}/cancel")
    SubscriptionService.View cancel(@PathVariable("id") long id) {
        return service.cancel(id);
    }

    @PostMapping("/subscriptions/{id}/change-plan")
    SubscriptionService.View changePlan(@PathVariable("id") long id,
                                        @RequestBody SubscriptionService.ChangePlanRequest request) {
        return service.changePlan(id, request);
    }

    /** trial শেষ হওয়া subscription গুলো হাতে প্রক্রিয়া করে। date না দিলে আজ (UTC)। */
    @PostMapping("/subscriptions/process-trials")
    Map<String, Integer> processTrials(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
        return Map.of("processed", service.processTrials(day));
    }

    // ---------- ভুলের উত্তর ----------

    @ExceptionHandler(IllegalTransitionException.class)
    ResponseEntity<Map<String, String>> notAllowed(IllegalTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String, String>> stale(OptimisticLockingFailureException e) {
        return error(HttpStatus.CONFLICT, "someone else changed this subscription, reload and try again");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", String.valueOf(message)));
    }
}