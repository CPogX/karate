/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import io.karatelabs.driver.DriverException;
import java.util.Map;

/** Describes a WebDriver BiDi protocol or transport failure. */
public class BidiException extends DriverException {

    private final String error;
    private final Map<String, Object> data;

    /** Creates an exception from a BiDi error response. */
    public BidiException(String error, String message, Map<String, Object> data) {
        super("WebDriver BiDi" + (error == null ? "" : " " + error) + ": "
                + (message == null ? "unknown error" : message));
        this.error = error;
        this.data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** Creates a client-side or transport failure. */
    public BidiException(String message, Throwable cause) {
        super(message, cause);
        this.error = null;
        this.data = Map.of();
    }

    /** Returns the protocol error code, if present. */
    public String getError() { return error; }

    /** Returns the decoded error frame. */
    public Map<String, Object> getData() { return data; }
}
