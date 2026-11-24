package com.meterflow.catalogue;

import java.math.BigDecimal;

/**
 * একটা ধাপ। upTo হলো মোট ইউনিটের উপরের সীমা (এই সংখ্যা সহ)।
 * শেষ ধাপে upTo থাকবে null, মানে কোনো সীমা নেই।
 */
public record PriceTier(BigDecimal upTo, BigDecimal unitPrice) {
}