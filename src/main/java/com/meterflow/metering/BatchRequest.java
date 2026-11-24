package com.meterflow.metering;

import java.util.List;

/** একটা request-এ আসা event-এর তালিকা। */
public record BatchRequest(List<EventRequest> events) {
}