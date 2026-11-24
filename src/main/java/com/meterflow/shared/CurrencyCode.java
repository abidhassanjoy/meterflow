package com.meterflow.shared;

public enum CurrencyCode {
    BDT(2),
    USD(2);

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }
}