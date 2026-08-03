/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import io.karatelabs.driver.Dialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** User prompt reported by the WebDriver BiDi browsing-context module. */
public final class BidiDialog implements Dialog {

    private static final Logger logger = LoggerFactory.getLogger(BidiDialog.class);

    private final BidiClient client;
    private final String context;
    private final String message;
    private final String type;
    private final String defaultPrompt;
    private final AtomicBoolean handled = new AtomicBoolean();

    BidiDialog(BidiClient client, Map<String, Object> params) {
        this.client = client;
        context = String.valueOf(params.get("context"));
        message = String.valueOf(params.getOrDefault("message", ""));
        type = String.valueOf(params.getOrDefault("type", "alert"));
        Object value = params.get("defaultValue");
        defaultPrompt = value == null ? null : value.toString();
    }

    @Override public String getMessage() { return message; }
    @Override public String getType() { return type; }
    @Override public String getDefaultPrompt() { return defaultPrompt; }
    @Override public void accept() { handle(true, null); }
    @Override public void accept(String promptText) { handle(true, promptText); }
    @Override public void dismiss() { handle(false, null); }
    @Override public boolean isHandled() { return handled.get(); }

    private void handle(boolean accept, String userText) {
        if (!handled.compareAndSet(false, true)) {
            return;
        }
        BidiCommand command = client.method("browsingContext.handleUserPrompt")
                .param("context", context)
                .param("accept", accept);
        if (userText != null) {
            command.param("userText", userText);
        }
        try {
            command.send().requireSuccess();
        } catch (RuntimeException e) {
            handled.set(false);
            logger.error("Unable to handle BiDi {} prompt in context {}: {}", type, context, e.getMessage());
            throw e;
        }
    }
}
