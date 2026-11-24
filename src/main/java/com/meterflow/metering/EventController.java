package com.meterflow.metering;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
class EventController {

    private final IngestionService service;

    EventController(IngestionService service) {
        this.service = service;
    }

    @PostMapping("/batch")
    BatchResponse ingest(@RequestBody BatchRequest request) {
        return service.ingest(request);
    }
}