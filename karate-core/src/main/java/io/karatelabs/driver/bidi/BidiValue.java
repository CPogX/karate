/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import java.util.Base64;
import java.util.Map;

/** Helpers for WebDriver BiDi byte values. */
public final class BidiValue {

    private BidiValue() { }

    /** Creates a UTF-8 string byte value. */
    public static Map<String, Object> stringBytes(String value) {
        return Map.of("type", "string", "value", value == null ? "" : value);
    }

    /** Creates a base64 byte value. */
    public static Map<String, Object> base64Bytes(byte[] value) {
        byte[] bytes = value == null ? new byte[0] : value;
        return Map.of("type", "base64", "value", Base64.getEncoder().encodeToString(bytes));
    }
}
