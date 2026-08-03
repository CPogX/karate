/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import io.karatelabs.http.HttpUtils;
import io.karatelabs.http.WsClient;
import io.karatelabs.http.WsClientOptions;
import io.karatelabs.http.WsException;
import net.minidev.json.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Concurrent WebDriver BiDi client using Karate's proxy-aware WebSocket transport.
 *
 * <p>Responses are correlated by command ID and may complete out of order.
 * Events are dispatched on one dedicated executor so user callbacks never block
 * WebSocket I/O or response routing.</p>
 */
public final class BidiClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BidiClient.class);
    private static final Set<BidiClient> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private record Pending(CompletableFuture<BidiResponse> future, String method) { }

    private final WsClient ws;
    private final Duration defaultTimeout;
    private final AtomicInteger idGenerator = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<BidiEvent>>> handlers = new ConcurrentHashMap<>();
    private final List<BidiEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService eventExecutor;
    private volatile boolean closed;

    /** Connects with a 30-second command timeout and inherited proxy settings. */
    public static BidiClient connect(String webSocketUrl) {
        return connect(webSocketUrl, Duration.ofSeconds(30), null);
    }

    /** Connects with a custom timeout and inherited proxy settings. */
    public static BidiClient connect(String webSocketUrl, Duration timeout) {
        return connect(webSocketUrl, timeout, null);
    }

    /** Connects with explicit/direct/JVM/environment proxy resolution. */
    public static BidiClient connect(String webSocketUrl, Duration timeout, Object proxy) {
        WsClientOptions options = WsClientOptions.builder(webSocketUrl)
                .disablePing()
                .maxPayloadSize(HttpUtils.MEGABYTE * 32)
                .connectTimeout(timeout)
                .proxy(proxy)
                .build();
        return new BidiClient(WsClient.connect(options), timeout);
    }

    BidiClient(WsClient ws, Duration defaultTimeout) {
        this.ws = ws;
        this.defaultTimeout = defaultTimeout;
        this.eventExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bidi-events-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        configureTransport();
        ACTIVE.add(this);
    }

    private void configureTransport() {
        ws.onMessage(frame -> {
            if (frame.isText()) {
                route(frame.getText());
            }
        });
        ws.onClose(() -> failPending(new WsException(WsException.Type.CONNECTION_CLOSED,
                "WebDriver BiDi websocket closed")));
        ws.onError(error -> {
            logger.error("WebDriver BiDi connection error: {}", error.getMessage());
            failPending(error);
        });
    }

    @SuppressWarnings("unchecked")
    private void route(String json) {
        Object decoded;
        try {
            decoded = JSONValue.parseWithException(json);
        } catch (Exception e) {
            logger.error("failed to parse WebDriver BiDi frame: {}", e.getMessage());
            return;
        }
        if (!(decoded instanceof Map<?, ?> decodedMap)) {
            return;
        }
        Map<String, Object> map = (Map<String, Object>) decodedMap;
        if (map.get("id") instanceof Number number) {
            Pending request = pending.remove(number.intValue());
            if (request != null) {
                request.future().complete(new BidiResponse(map));
            }
            return;
        }
        if (map.get("method") instanceof String method) {
            BidiEvent event = new BidiEvent(map);
            eventExecutor.execute(() -> dispatch(method, event));
        }
    }

    private void dispatch(String method, BidiEvent event) {
        List<Consumer<BidiEvent>> methodHandlers = handlers.get(method);
        if (methodHandlers != null) {
            for (Consumer<BidiEvent> handler : methodHandlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    logger.error("WebDriver BiDi handler failed for {}: {}", method, e.getMessage());
                }
            }
        }
        for (BidiEventListener listener : listeners) {
            try {
                listener.onEvent(method, event.getParams());
            } catch (Exception e) {
                logger.error("WebDriver BiDi listener failed for {}: {}", method, e.getMessage());
            }
        }
    }

    /** Creates a command builder with a unique command identifier. */
    public BidiCommand method(String method) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("WebDriver BiDi method cannot be blank");
        }
        return new BidiCommand(this, idGenerator.incrementAndGet(), method);
    }

    BidiResponse send(BidiCommand command) {
        try {
            return sendAsync(command).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new BidiException("WebDriver BiDi timeout for " + command.getMethod(), cause);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BidiException("WebDriver BiDi command failed: " + command.getMethod(), cause);
        }
    }

    CompletableFuture<BidiResponse> sendAsync(BidiCommand command) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(new WsException(
                    WsException.Type.CONNECTION_CLOSED, "WebDriver BiDi websocket is not open"));
        }
        CompletableFuture<BidiResponse> future = new CompletableFuture<>();
        pending.put(command.getId(), new Pending(future, command.getMethod()));
        try {
            ws.send(command.toJson());
        } catch (Exception e) {
            pending.remove(command.getId());
            return CompletableFuture.failedFuture(e);
        }
        Duration timeout = command.getTimeout() == null ? defaultTimeout : command.getTimeout();
        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        pending.remove(command.getId());
                    }
                });
    }

    /** Adds a handler for one subscribed protocol event. */
    public void on(String eventName, Consumer<BidiEvent> handler) {
        handlers.computeIfAbsent(eventName, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Removes one protocol event handler. */
    public void off(String eventName, Consumer<BidiEvent> handler) {
        List<Consumer<BidiEvent>> methodHandlers = handlers.get(eventName);
        if (methodHandlers != null) {
            methodHandlers.remove(handler);
        }
    }

    /** Removes every local handler for an event. */
    public void offAll(String eventName) { handlers.remove(eventName); }

    /** Adds an observer that receives all routed events. */
    public void addEventListener(BidiEventListener listener) { listeners.add(listener); }

    /** Removes an all-event observer. */
    public void removeEventListener(BidiEventListener listener) { listeners.remove(listener); }

    /** Returns whether the underlying WebSocket is open. */
    public boolean isOpen() { return !closed && ws.isOpen(); }

    /** Returns the default command timeout. */
    public Duration getDefaultTimeout() { return defaultTimeout; }

    private void failPending(Throwable error) {
        pending.values().forEach(request -> request.future().completeExceptionally(error));
        pending.clear();
    }

    /** Closes the connection and fails all outstanding commands. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ACTIVE.remove(this);
        failPending(new WsException(WsException.Type.CONNECTION_CLOSED, "WebDriver BiDi client closed"));
        ws.close();
        eventExecutor.shutdownNow();
    }

    /** Closes every BiDi client created in this JVM. */
    public static void closeAll() {
        List.copyOf(ACTIVE).forEach(BidiClient::close);
    }
}
