/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import io.karatelabs.driver.Driver;
import io.karatelabs.driver.PooledDriverProvider;
import io.karatelabs.driver.e2e.support.TestPageServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-browser Selenium Grid and pooled-session validation for the BiDi backend. */
@EnabledIfSystemProperty(named = "karate.bidi.gridUrl", matches = ".+")
class BidiGridE2eTest {

    private static final int SERVER_PORT = 18084;
    private static TestPageServer server;

    @BeforeAll
    static void startServer() {
        server = TestPageServer.start(SERVER_PORT);
        System.setProperty("karate.bidi.serverUrl",
                System.getProperty("karate.bidi.serverUrl", "http://host.docker.internal:" + SERVER_PORT));
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stopAndWait();
        }
    }

    @Test
    void realPagesMockDelegationDialogsAndPdf() {
        PooledDriverProvider provider = new PooledDriverProvider(1);
        SuiteResult result = run("bidi-grid-e2e", provider, 1);
        assertTrue(result.isPassed(), () -> String.join(System.lineSeparator(), result.getErrors()));
    }

    @Test
    void pooledDriverUsesTwoRealGridSessions() {
        AtomicInteger created = new AtomicInteger();
        PooledDriverProvider provider = new PooledDriverProvider(2) {
            @Override
            protected Driver createDriver(Map<String, Object> config) {
                created.incrementAndGet();
                return super.createDriver(config);
            }
        };
        SuiteResult result = run("bidi-grid-pool", provider, 2);
        assertTrue(result.isPassed(), () -> String.join(System.lineSeparator(), result.getErrors()));
        assertEquals(2, created.get(), "parallel pool should create exactly two Grid browser sessions");
    }

    private static SuiteResult run(String feature, PooledDriverProvider provider, int threads) {
        String browser = System.getProperty("karate.bidi.browserName", "chrome").toLowerCase();
        return Runner.path("classpath:io/karatelabs/driver/bidi/" + feature + ".feature")
                .outputDir(Path.of("target", "karate-reports", feature + "-" + browser))
                .outputHtmlReport(true)
                .outputConsoleSummary(true)
                .driverProvider(provider)
                .parallel(threads);
    }
}
