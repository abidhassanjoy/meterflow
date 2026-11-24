package com.meterflow;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifyModuleStructure() {
        // ৬টা module-এর মধ্যে নিষিদ্ধ নির্ভরতা আছে কি না যাচাই করে
        ApplicationModules.of(MeterflowApplication.class).verify();
    }
}