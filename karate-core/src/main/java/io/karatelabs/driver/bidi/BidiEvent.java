/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import java.util.Collections;
import java.util.Map;

/** Immutable view of a WebDriver BiDi event frame. */
public final class BidiEvent {

    private final String method;
    private final Map<String, Object> params;
    private final Map<String, Object> raw;

    @SuppressWarnings("unchecked")
    BidiEvent(Map<String, Object> raw) {
        this.raw = Collections.unmodifiableMap(raw);
        this.method = String.valueOf(raw.get("method"));
        Object value = raw.get("params");
        this.params = value instanceof Map<?, ?> map
                ? Collections.unmodifiableMap((Map<String, Object>) map) : Map.of();
    }

    /** Returns the event method. */
    public String getMethod() { return method; }

    /** Returns the event parameters. */
    public Map<String, Object> getParams() { return params; }

    /** Returns the complete decoded frame. */
    public Map<String, Object> getRaw() { return raw; }
}
