/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import java.util.Collections;
import java.util.Map;

/** Immutable WebDriver BiDi command response. */
public final class BidiResponse {

    private final Map<String, Object> raw;

    BidiResponse(Map<String, Object> raw) {
        this.raw = Collections.unmodifiableMap(raw);
    }

    /** Returns the numeric command identifier. */
    public int getId() {
        Object id = raw.get("id");
        return id instanceof Number number ? number.intValue() : -1;
    }

    /** Returns whether the remote end returned a protocol error. */
    public boolean isError() {
        return "error".equals(raw.get("type")) || raw.containsKey("error");
    }

    /** Returns the result object, or an empty map. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getResult() {
        Object result = raw.get("result");
        return result instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** Returns the protocol error code, if present. */
    public String getError() { return raw.get("error") == null ? null : raw.get("error").toString(); }

    /** Returns the remote error message, if present. */
    public String getMessage() { return raw.get("message") == null ? null : raw.get("message").toString(); }

    /** Returns the complete decoded frame. */
    public Map<String, Object> getRaw() { return raw; }

    /** Throws a {@link BidiException} for an error response. */
    public BidiResponse requireSuccess() {
        if (isError()) {
            throw new BidiException(getError(), getMessage(), raw);
        }
        return this;
    }
}
