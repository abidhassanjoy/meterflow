package com.meterflow.catalogue;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.meterflow.catalogue.ChargeSupport.*;

/** প্রতি packageSize ইউনিটে একটা packagePrice। অর্ধেক package-ও পুরো package ধরা হয়। */
public record PackageCharge(BigDecimal packageSize, BigDecimal packagePrice) implements Charge {

    public PackageCharge {
        if (packageSize == null || packageSize.signum() <= 0) {
            throw new IllegalArgumentException("package size must be more than zero");
        }
        checkPrice(packagePrice, "package price");
    }

    @Override
    public String type() {
        return "PACKAGE";
    }

    @Override
    public ChargeResult calculate(BigDecimal quantity) {
        checkQuantity(quantity);
        BigDecimal packages = quantity.divide(packageSize, 0, RoundingMode.CEILING);  // উপরের দিকে গোল
        BigDecimal amount = packages.multiply(packagePrice);
        return new ChargeResult(quantity, round(amount),
                plain(quantity) + " units = " + plain(packages) + " packages of "
                        + plain(packageSize) + " x " + plain(packagePrice));
    }
}