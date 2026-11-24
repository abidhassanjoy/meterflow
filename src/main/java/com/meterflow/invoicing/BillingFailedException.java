package com.meterflow.invoicing;

import java.util.List;

/** এক chunk-এর এক বা একাধিক subscription-এর invoice বানানো ব্যর্থ হয়েছে। Batch step এটা skip করে এগোয়। */
class BillingFailedException extends RuntimeException {

    BillingFailedException(List<String> failures) {
        super(String.join("; ", failures));
    }
}