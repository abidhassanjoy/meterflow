package com.meterflow.invoicing;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class CreditNoteController {

    private final CreditNoteService service;

    CreditNoteController(CreditNoteService service) {
        this.service = service;
    }

    @PostMapping("/invoices/{id}/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    CreditNoteService.Note create(@PathVariable("id") long id,
                                  @RequestBody CreditNoteService.CreateRequest request) {
        return service.create(id, request);
    }

    @GetMapping("/invoices/{id}/credit-notes")
    List<CreditNoteService.Note> list(@PathVariable("id") long id) {
        return service.list(id);
    }
}