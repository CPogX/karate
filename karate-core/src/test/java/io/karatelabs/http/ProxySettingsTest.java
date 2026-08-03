/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ProxySettingsTest {

    @Test
    void explicitWebSocketProxyWinsOverJvmAndEnvironment() {
        Properties properties = new Properties();
        properties.setProperty("https.proxyHost", "jvm-proxy");
        Map<String, Object> config = Map.of(
                "proxy", "http://generic:8080",
                "webSocketProxy", "socks5://user:pass@ws-proxy:1080");

        ProxySettings result = ProxySettings.resolve(URI.create("wss://grid.example/ws"), config,
                properties, Map.of("HTTPS_PROXY", "http://env-proxy:3128"));

        assertNotNull(result);
        assertEquals(ProxySettings.Source.DIRECT, result.getSource());
        assertEquals(ProxySettings.Type.SOCKS5, result.getType());
        assertEquals("ws-proxy", result.getHost());
        assertEquals(1080, result.getPort());
        assertEquals("user", result.getUsername());
        assertEquals("pass", result.getPassword());
        assertFalse(result.scrubbedUri().contains("user"));
        assertFalse(result.scrubbedUri().contains("pass"));
    }

    @Test
    void explicitDirectDisablesInheritedProxy() {
        Properties properties = new Properties();
        properties.setProperty("http.proxyHost", "jvm-proxy");

        ProxySettings result = ProxySettings.resolve(URI.create("http://grid.example"),
                Map.of("httpProxy", false), properties,
                Map.of("HTTP_PROXY", "http://env-proxy:3128"));

        assertNull(result);
    }

    @Test
    void jvmProxyWinsOverEnvironmentAndHonorsBypass() {
        Properties properties = new Properties();
        properties.setProperty("https.proxyHost", "jvm-proxy");
        properties.setProperty("https.proxyPort", "8443");
        properties.setProperty("http.nonProxyHosts", "localhost|*.internal.example");

        ProxySettings result = ProxySettings.resolve(URI.create("https://grid.example"), null,
                properties, Map.of("HTTPS_PROXY", "http://env-proxy:3128"));
        assertNotNull(result);
        assertEquals(ProxySettings.Source.JVM, result.getSource());
        assertEquals("jvm-proxy", result.getHost());
        assertEquals(8443, result.getPort());

        assertNull(ProxySettings.resolve(URI.create("https://grid.internal.example"), null,
                properties, Map.of()));
    }

    @Test
    void environmentProxyHonorsNoProxy() {
        Map<String, String> environment = Map.of(
                "HTTPS_PROXY", "http://env-proxy:3128",
                "NO_PROXY", "localhost,.tunnel.example");

        ProxySettings result = ProxySettings.resolve(URI.create("wss://grid.example/ws"), null,
                new Properties(), environment);
        assertNotNull(result);
        assertEquals(ProxySettings.Source.ENVIRONMENT, result.getSource());
        assertEquals("env-proxy", result.getHost());
        assertNull(ProxySettings.resolve(URI.create("wss://local.tunnel.example/ws"), null,
                new Properties(), environment));
    }

    @Test
    void httpAndWebSocketCanUseDifferentDirectProxies() {
        Map<String, Object> config = Map.of(
                "httpProxy", "http://http-proxy:8080",
                "webSocketProxy", "http://ws-proxy:8081");

        assertEquals("http-proxy", ProxySettings.resolve(URI.create("https://grid.example"),
                config, new Properties(), Map.of()).getHost());
        assertEquals("ws-proxy", ProxySettings.resolve(URI.create("wss://grid.example/ws"),
                config, new Properties(), Map.of()).getHost());
    }
}
