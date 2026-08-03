/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BidiProtocolTest {

    @Test
    void successAndErrorResponsesAreDistinguished() {
        BidiResponse success = new BidiResponse(Map.of("type", "success", "id", 7,
                "result", Map.of("ready", true)));
        assertEquals(7, success.getId());
        assertTrue((Boolean) success.requireSuccess().getResult().get("ready"));

        BidiResponse error = new BidiResponse(Map.of("type", "error", "id", 8,
                "error", "invalid argument", "message", "bad value"));
        BidiException exception = assertThrows(BidiException.class, error::requireSuccess);
        assertEquals("invalid argument", exception.getError());
        assertTrue(exception.getMessage().contains("bad value"));
    }

    @Test
    void byteValuesUseTheRequiredWireShape() {
        assertEquals(Map.of("type", "string", "value", "hello"), BidiValue.stringBytes("hello"));
        Map<String, Object> encoded = BidiValue.base64Bytes("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("base64", encoded.get("type"));
        assertEquals("aGVsbG8=", encoded.get("value"));
    }

    @Test
    void eventsExposeImmutableParameters() {
        BidiEvent event = new BidiEvent(Map.of("type", "event", "method", "log.entryAdded",
                "params", Map.of("text", "ready")));
        assertEquals("log.entryAdded", event.getMethod());
        assertEquals("ready", event.getParams().get("text"));
        assertThrows(UnsupportedOperationException.class, () -> event.getParams().put("x", "y"));
    }
}
