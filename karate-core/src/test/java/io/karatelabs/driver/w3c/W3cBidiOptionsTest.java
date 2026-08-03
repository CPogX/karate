/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.w3c;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class W3cBidiOptionsTest {

    @Test
    @SuppressWarnings("unchecked")
    void bidiTypeRequestsStandardCapability() {
        W3cDriverOptions options = W3cDriverOptions.fromMap(Map.of(
                "type", "bidi",
                "browserName", "firefox",
                "webDriverUrl", "http://localhost:4444"));

        assertTrue(options.isBidi());
        assertEquals(W3cBrowserType.GECKODRIVER, options.getBrowserType());
        Map<String, Object> capabilities = (Map<String, Object>) options.buildSessionPayload().get("capabilities");
        Map<String, Object> alwaysMatch = (Map<String, Object>) capabilities.get("alwaysMatch");
        assertEquals("firefox", alwaysMatch.get("browserName"));
        assertEquals(true, alwaysMatch.get("webSocketUrl"));
        assertEquals("ignore", alwaysMatch.get("unhandledPromptBehavior"));
    }

    @Test
    void existingW3cTypeCanOptIntoBidi() {
        W3cDriverOptions options = W3cDriverOptions.fromMap(Map.of(
                "type", "chromedriver", "bidi", true));
        assertTrue(options.isBidi());
        assertEquals(W3cBrowserType.CHROMEDRIVER, options.getBrowserType());
    }

    @Test
    void bidiRequiresAnExplicitBrowser() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> W3cDriverOptions.fromMap(Map.of("type", "bidi")));
        assertTrue(error.getMessage().contains("browserName"));
    }
}
