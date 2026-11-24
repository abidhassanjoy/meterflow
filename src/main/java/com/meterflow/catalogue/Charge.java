package com.meterflow.catalogue;

import java.math.BigDecimal;

/**
 * একটা দামের নিয়ম। প্রতিটা ধরন আলাদা class (Strategy pattern)।
 * নতুন ধরন চাইলে শুধু নতুন একটা class লিখলেই হবে, পুরনো কোড বদলাতে হবে না।
 */
public interface Charge {

    /** যেমন "FLAT", "GRADUATED" */
    String type();

    /** usage-এর পরিমাণ (সিটের ক্ষেত্রে সিটের সংখ্যা) থেকে টাকা বের করে */
    ChargeResult calculate(BigDecimal quantity);
}