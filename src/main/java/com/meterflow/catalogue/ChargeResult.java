package com.meterflow.catalogue;

import java.math.BigDecimal;

/**
 * একটা charge-এর হিসাবের ফলাফল। rule-এ লেখা থাকে কীভাবে এই টাকা এলো,
 * যাতে invoice-এর প্রতিটা line ব্যাখ্যা করা যায়।
 */
public record ChargeResult(BigDecimal quantity, BigDecimal amount, String rule) {
}