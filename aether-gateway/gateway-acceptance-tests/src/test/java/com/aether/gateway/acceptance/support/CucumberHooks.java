package com.aether.gateway.acceptance.support;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;

public class CucumberHooks {

    @BeforeAll
    public static void startEnvironment() throws Exception {
        AcceptanceEnvironment.start();
    }

    @AfterAll
    public static void stopEnvironment() {
        AcceptanceEnvironment.stop();
    }
}
