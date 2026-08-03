/* The MIT License - Copyright 2025 Karate Labs Inc. */
package io.karatelabs.driver.bidi;

import io.karatelabs.driver.Dialog;
import io.karatelabs.driver.DialogHandler;
import io.karatelabs.driver.DriverException;
import io.karatelabs.driver.InterceptHandler;
import io.karatelabs.driver.InterceptRequest;
import io.karatelabs.driver.InterceptResponse;
import io.karatelabs.driver.w3c.W3cDriver;
import io.karatelabs.driver.w3c.W3cDriverOptions;
import io.karatelabs.driver.w3c.W3cSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * WebDriver BiDi extension of Karate's complete W3C WebDriver backend.
 *
 * <p>Stable command operations remain on classic WebDriver. The negotiated
 * BiDi channel supplies streaming capabilities that classic WebDriver lacks:
 * request interception, prompt events, and browser-native PDF printing. This
 * hybrid follows the W3C migration model and keeps cloud/grid compatibility.</p>
 */
public final class BidiDriver extends W3cDriver {

    private static final Logger logger = LoggerFactory.getLogger(BidiDriver.class);

    private final BidiClient client;
    private final ExecutorService interceptExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "karate-bidi-intercept");
        thread.setDaemon(true);
        return thread;
    });
    private volatile DialogHandler dialogHandler;
    private volatile BidiDialog currentDialog;
    private volatile String interceptId;
    private volatile InterceptHandler interceptHandler;
    private volatile List<Pattern> interceptPatterns = List.of();

    private final Consumer<BidiEvent> promptOpened = this::onPromptOpened;
    private final Consumer<BidiEvent> promptClosed = event -> currentDialog = null;
    private final Consumer<BidiEvent> beforeRequest = this::onBeforeRequest;

    private BidiDriver(W3cSession session, W3cDriverOptions options,
                       io.karatelabs.process.ProcessHandle process) {
        super(session, options, process);
        String webSocketUrl = resolveWebSocketUrl(session, options);
        if (webSocketUrl == null || webSocketUrl.isBlank()) {
            try {
                session.deleteSession();
            } catch (Exception ignored) {
                // retain the useful missing-capability error below
            }
            if (process != null) {
                process.close(true);
            }
            throw new DriverException("WebDriver session did not return the requested webSocketUrl capability");
        }
        client = BidiClient.connect(webSocketUrl, options.getTimeoutDuration(), options.getTransportProxy());
        client.on("browsingContext.userPromptOpened", promptOpened);
        client.on("browsingContext.userPromptClosed", promptClosed);
        subscribe(List.of("browsingContext.userPromptOpened", "browsingContext.userPromptClosed"));
    }

    static String resolveWebSocketUrl(W3cSession session, W3cDriverOptions options) {
        if (options.getBidiWebSocketUrl() != null && !options.getBidiWebSocketUrl().isBlank()) {
            return options.getBidiWebSocketUrl();
        }
        String negotiated = session.getWebSocketUrl();
        String webDriverUrl = options.getWebDriverUrl();
        if (negotiated == null || webDriverUrl == null || !options.isRewriteWebSocketUrl()) {
            return negotiated;
        }
        URI bidiUri = URI.create(negotiated);
        URI driverUri = URI.create(webDriverUrl);
        if (!shouldRewriteAuthority(bidiUri.getHost(), driverUri.getHost())) {
            return negotiated;
        }
        try {
            String scheme = "https".equalsIgnoreCase(driverUri.getScheme()) ? "wss" : "ws";
            String userInfo = bidiUri.getUserInfo() != null ? bidiUri.getUserInfo() : driverUri.getUserInfo();
            URI rewritten = new URI(scheme, userInfo, driverUri.getHost(), driverUri.getPort(),
                    bidiUri.getPath(), bidiUri.getQuery(), bidiUri.getFragment());
            logger.info("Rewriting private BiDi endpoint authority {} to externally reachable {}:{}",
                    bidiUri.getAuthority(), driverUri.getHost(), driverUri.getPort());
            return rewritten.toString();
        } catch (URISyntaxException e) {
            throw new DriverException("Unable to normalize WebDriver BiDi endpoint: " + negotiated, e);
        }
    }

    private static boolean shouldRewriteAuthority(String bidiHost, String driverHost) {
        if (bidiHost == null || driverHost == null || bidiHost.equalsIgnoreCase(driverHost)) {
            return false;
        }
        String host = bidiHost.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.equals("0.0.0.0") || host.startsWith("127.")) {
            return true;
        }
        if (host.startsWith("10.") || host.startsWith("192.168.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Starts a local or remote BiDi-enabled W3C session. */
    public static BidiDriver start(W3cDriverOptions options) {
        StartedSession started = startSession(options);
        return new BidiDriver(started.session(), options, started.process());
    }

    /** Connects to a remote WebDriver server and negotiates a BiDi channel. */
    public static BidiDriver connect(String webDriverUrl, W3cDriverOptions options) {
        W3cSession session = W3cSession.create(webDriverUrl, options.buildSessionPayload(),
                options.getTimeoutDuration(), options.getTransportProxy());
        return new BidiDriver(session, options, null);
    }

    /** Returns the low-level correlated BiDi client for advanced protocol use. */
    public BidiClient getBidiClient() {
        return client;
    }

    private void subscribe(List<String> events) {
        client.method("session.subscribe").param("events", events).send().requireSuccess();
    }

    @Override
    public void setUrl(String url) {
        client.method("browsingContext.navigate")
                .param("context", session.getWindowHandle())
                .param("url", url)
                .param("wait", "complete")
                .send().requireSuccess();
    }

    @Override
    public void refresh() {
        client.method("browsingContext.reload")
                .param("context", session.getWindowHandle())
                .param("wait", "complete")
                .send().requireSuccess();
    }

    @Override
    public void back() {
        traverseHistory(-1);
    }

    @Override
    public void forward() {
        traverseHistory(1);
    }

    private void traverseHistory(int delta) {
        client.method("browsingContext.traverseHistory")
                .param("context", session.getWindowHandle())
                .param("delta", delta)
                .send().requireSuccess();
    }

    @Override
    public byte[] pdf(Map<String, Object> pdfOptions) {
        BidiCommand command = client.method("browsingContext.print")
                .param("context", session.getWindowHandle());
        if (pdfOptions != null) {
            command.params(pdfOptions);
        }
        Object data = command.send().requireSuccess().getResult().get("data");
        if (data == null) {
            throw new DriverException("WebDriver BiDi print response did not contain data");
        }
        return Base64.getDecoder().decode(data.toString());
    }

    @Override
    public void onDialog(DialogHandler handler) {
        dialogHandler = handler;
    }

    @Override
    public String getDialogText() {
        BidiDialog dialog = currentDialog;
        return dialog == null ? super.getDialogText() : dialog.getMessage();
    }

    @Override
    public Dialog getDialog() {
        BidiDialog dialog = currentDialog;
        if (dialog != null && dialog.isHandled()) {
            currentDialog = null;
            dialog = null;
        }
        return dialog == null ? super.getDialog() : dialog;
    }

    @Override
    public void dialog(boolean accept, String input) {
        BidiDialog dialog = currentDialog;
        if (dialog == null) {
            super.dialog(accept, input);
            return;
        }
        if (accept) {
            dialog.accept(input);
        } else {
            dialog.dismiss();
        }
    }

    private void onPromptOpened(BidiEvent event) {
        BidiDialog dialog = new BidiDialog(client, event.getParams());
        currentDialog = dialog;
        DialogHandler handler = dialogHandler;
        if (handler != null) {
            handler.handle(dialog);
        }
    }

    @Override
    public void intercept(InterceptHandler handler) {
        intercept(List.of("*"), handler);
    }

    @Override
    public synchronized void intercept(List<String> patterns, InterceptHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("intercept handler cannot be null");
        }
        stopIntercept();
        interceptHandler = handler;
        interceptPatterns = compilePatterns(patterns);
        client.on("network.beforeRequestSent", beforeRequest);
        subscribe(List.of("network.beforeRequestSent"));
        Map<String, Object> result = client.method("network.addIntercept")
                .param("phases", List.of("beforeRequestSent"))
                .send().requireSuccess().getResult();
        interceptId = String.valueOf(result.get("intercept"));
    }

    @Override
    public synchronized void stopIntercept() {
        String id = interceptId;
        interceptId = null;
        interceptHandler = null;
        interceptPatterns = List.of();
        if (client != null) {
            client.off("network.beforeRequestSent", beforeRequest);
        }
        if (id != null) {
            try {
                client.method("network.removeIntercept").param("intercept", id).send().requireSuccess();
            } catch (Exception e) {
                logger.debug("Unable to remove BiDi intercept {}: {}", id, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void onBeforeRequest(BidiEvent event) {
        InterceptHandler handler = interceptHandler;
        if (handler == null || !Boolean.TRUE.equals(event.getParams().get("isBlocked"))) {
            return;
        }
        Object value = event.getParams().get("request");
        if (!(value instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> request = (Map<String, Object>) value;
        String id = String.valueOf(request.get("request"));
        String url = String.valueOf(request.get("url"));
        if (!matches(url)) {
            continueRequest(id);
            return;
        }
        interceptExecutor.execute(() -> handleIntercept(handler, id, url, request));
    }

    private void handleIntercept(InterceptHandler handler, String id, String url,
                                 Map<String, Object> request) {
        try {
            InterceptRequest intercepted = new InterceptRequest(id, url,
                    String.valueOf(request.get("method")), headerMap(request.get("headers")),
                    requestBody(request), resourceType(request));
            InterceptResponse response = handler.handle(intercepted);
            if (response == null) {
                continueRequest(id);
            } else {
                provideResponse(id, response);
            }
        } catch (Exception e) {
            logger.error("BiDi intercept handler failed for {}: {}", url, e.getMessage());
            continueRequest(id);
        }
    }

    private void continueRequest(String id) {
        client.method("network.continueRequest").param("request", id).send().requireSuccess();
    }

    private void provideResponse(String id, InterceptResponse response) {
        client.method("network.provideResponse")
                .param("request", id)
                .param("statusCode", response.getStatus())
                .param("headers", bidiHeaders(response.getHeaders()))
                .param("body", BidiValue.base64Bytes(response.getBody()))
                .send().requireSuccess();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> headerMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof List<?> headers) {
            for (Object item : headers) {
                if (item instanceof Map<?, ?> raw) {
                    Object name = raw.get("name");
                    Object headerValue = raw.get("value");
                    if (name != null) {
                        result.put(name.toString(), byteValue(headerValue));
                    }
                }
            }
        } else if (value instanceof Map<?, ?> headers) {
            headers.forEach((name, headerValue) -> result.put(String.valueOf(name), headerValue));
        }
        return result;
    }

    private static String byteValue(Object value) {
        if (value instanceof Map<?, ?> bytes && bytes.get("value") != null) {
            return bytes.get("value").toString();
        }
        return value == null ? "" : value.toString();
    }

    private static String requestBody(Map<String, Object> request) {
        Object body = request.get("body");
        return body == null ? null : byteValue(body);
    }

    private static String resourceType(Map<String, Object> request) {
        Object destination = request.get("destination");
        return destination == null || destination.toString().isBlank() ? "Other" : destination.toString();
    }

    private static List<Map<String, Object>> bidiHeaders(Map<String, Object> headers) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (headers != null) {
            headers.forEach((name, value) -> result.add(Map.of(
                    "name", name,
                    "value", BidiValue.stringBytes(value == null ? "" : value.toString()))));
        }
        return result;
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> result = new ArrayList<>();
        List<String> values = patterns == null || patterns.isEmpty() ? List.of("*") : patterns;
        for (String value : values) {
            StringBuilder regex = new StringBuilder("^");
            for (char character : value.toCharArray()) {
                if (character == '*') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            result.add(Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE));
        }
        return result;
    }

    private boolean matches(String url) {
        return interceptPatterns.stream().anyMatch(pattern -> pattern.matcher(url).matches());
    }

    @Override
    public synchronized void quit() {
        if (terminated) {
            return;
        }
        try {
            stopIntercept();
        } catch (Exception ignored) {
            // cleanup continues
        }
        client.close();
        interceptExecutor.shutdownNow();
        super.quit();
    }
}
