/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import net.minidev.json.JSONValue;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Fluent, single-use builder for a WebDriver BiDi command. */
public final class BidiCommand {

    private final BidiClient client;
    private final int id;
    private final String method;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private Duration timeout;

    BidiCommand(BidiClient client, int id, String method) {
        this.client = client;
        this.id = id;
        this.method = method;
    }

    /** Adds a non-null command parameter. */
    public BidiCommand param(String name, Object value) {
        if (value != null) {
            params.put(name, value);
        }
        return this;
    }

    /** Adds all command parameters. */
    public BidiCommand params(Map<String, Object> values) {
        if (values != null) {
            values.forEach(this::param);
        }
        return this;
    }

    /** Overrides the default command timeout. */
    public BidiCommand timeout(Duration value) {
        timeout = value;
        return this;
    }

    /** Sends the command and waits for its correlated response. */
    public BidiResponse send() { return client.send(this); }

    /** Sends the command asynchronously. */
    public CompletableFuture<BidiResponse> sendAsync() { return client.sendAsync(this); }

    /** Serializes the command to its wire representation. */
    public String toJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("method", method);
        value.put("params", params);
        return JSONValue.toJSONString(value);
    }

    int getId() { return id; }
    String getMethod() { return method; }
    Duration getTimeout() { return timeout; }
}
