/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import java.util.Map;

/** Receives every event observed by a {@link BidiClient}. */
@FunctionalInterface
public interface BidiEventListener {

    /** Handles one event on the client's serialized event executor. */
    void onEvent(String method, Map<String, Object> params);
}
