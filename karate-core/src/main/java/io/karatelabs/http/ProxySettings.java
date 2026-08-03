/*
 * The MIT License
 *
 * Copyright 2025 Karate Labs Inc.
 */
package io.karatelabs.http;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves transport proxies consistently for Karate HTTP and WebSocket clients.
 *
 * <p>Resolution order is explicit driver configuration, JVM properties, then
 * environment variables. An explicit value of {@code false}, {@code direct},
 * {@code none}, or {@code off} disables inherited proxy settings. Supported
 * schemes are HTTP, SOCKS4, and SOCKS5.</p>
 */
public final class ProxySettings {

    /** Supported transport proxy protocols. */
    public enum Type { HTTP, SOCKS4, SOCKS5 }

    /** Describes where a resolved proxy came from. */
    public enum Source { DIRECT, JVM, ENVIRONMENT }

    private record Selection(boolean present, Object value) { }

    private final Type type;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final List<String> nonProxyHosts;
    private final Source source;

    private ProxySettings(Type type, String host, int port, String username, String password,
                          List<String> nonProxyHosts, Source source) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.nonProxyHosts = nonProxyHosts == null ? List.of() : List.copyOf(nonProxyHosts);
        this.source = source;
    }

    /** Resolves a proxy using the process JVM properties and environment. */
    public static ProxySettings resolve(URI targetUri, Object explicitProxy) {
        return resolve(targetUri, explicitProxy, System.getProperties(), System.getenv());
    }

    /**
     * Resolves a proxy using supplied sources. This overload exists so precedence
     * and bypass behavior can be tested without changing process-global state.
     */
    public static ProxySettings resolve(URI targetUri, Object explicitProxy, Properties properties,
                                        Map<String, String> environment) {
        if (targetUri == null) {
            return null;
        }
        Selection selection = selectExplicit(explicitProxy, targetUri);
        if (selection.present()) {
            if (isDirect(selection.value())) {
                return null;
            }
            ProxySettings direct = fromConfig(selection.value(), Source.DIRECT);
            return direct != null && !direct.shouldBypass(targetUri.getHost()) ? direct : null;
        }
        ProxySettings inherited = fromSystemProperties(targetUri, properties);
        if (inherited == null) {
            inherited = fromEnvironment(targetUri, environment);
        }
        return inherited != null && !inherited.shouldBypass(targetUri.getHost()) ? inherited : null;
    }

    @SuppressWarnings("unchecked")
    private static ProxySettings fromConfig(Object value, Source source) {
        if (value == null) {
            return null;
        }
        if (value instanceof ProxySettings settings) {
            return settings;
        }
        if (value instanceof String text) {
            return fromUri(text, null, null, List.of(), source);
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("proxy config must be a string or map");
        }
        Map<String, Object> map = (Map<String, Object>) value;
        String uri = firstNonBlank(string(map.get("uri")), string(map.get("url")),
                string(map.get("proxyUri")), string(map.get("proxyUrl")));
        List<String> bypass = toStringList(map.get("nonProxyHosts"));
        if (bypass.isEmpty()) {
            bypass = splitBypass(firstNonBlank(string(map.get("noProxy")), string(map.get("noProxyHosts"))));
        }
        String username = firstNonBlank(string(map.get("username")), string(map.get("user")),
                string(map.get("proxyUser")));
        String password = firstNonBlank(string(map.get("password")), string(map.get("proxyPassword")));
        if (uri != null) {
            return fromUri(uri, username, password, bypass, source);
        }
        String host = firstNonBlank(string(map.get("host")), string(map.get("proxyHost")));
        if (host == null) {
            return null;
        }
        Type type = typeOf(firstNonBlank(string(map.get("type")), "http"));
        int port = integer(firstNonBlank(string(map.get("port")), string(map.get("proxyPort"))),
                type == Type.HTTP ? 80 : 1080);
        return new ProxySettings(type, host, port, username, password, bypass, source);
    }

    private static ProxySettings fromSystemProperties(URI targetUri, Properties properties) {
        boolean secure = isSecure(targetUri);
        ProxySettings result = fromHttpProperties(secure ? "https" : "http", properties);
        if (result == null && secure) {
            result = fromHttpProperties("http", properties);
        }
        if (result != null) {
            return result;
        }
        String host = blankToNull(properties.getProperty("socksProxyHost"));
        if (host == null) {
            return null;
        }
        Type type = integer(properties.getProperty("socksProxyVersion"), 5) == 4 ? Type.SOCKS4 : Type.SOCKS5;
        return new ProxySettings(type, host, integer(properties.getProperty("socksProxyPort"), 1080),
                blankToNull(properties.getProperty("java.net.socks.username")),
                blankToNull(properties.getProperty("java.net.socks.password")), List.of(), Source.JVM);
    }

    private static ProxySettings fromHttpProperties(String prefix, Properties properties) {
        String host = blankToNull(properties.getProperty(prefix + ".proxyHost"));
        if (host == null) {
            return null;
        }
        String bypass = properties.getProperty(prefix + ".nonProxyHosts",
                properties.getProperty("http.nonProxyHosts"));
        return new ProxySettings(Type.HTTP, host, integer(properties.getProperty(prefix + ".proxyPort"), 80),
                blankToNull(properties.getProperty(prefix + ".proxyUser")),
                blankToNull(properties.getProperty(prefix + ".proxyPassword")),
                splitBypass(bypass), Source.JVM);
    }

    private static ProxySettings fromEnvironment(URI targetUri, Map<String, String> environment) {
        String raw = isSecure(targetUri)
                ? firstNonBlank(environment.get("HTTPS_PROXY"), environment.get("https_proxy"),
                environment.get("HTTP_PROXY"), environment.get("http_proxy"))
                : firstNonBlank(environment.get("HTTP_PROXY"), environment.get("http_proxy"));
        raw = firstNonBlank(raw, environment.get("ALL_PROXY"), environment.get("all_proxy"));
        if (raw == null) {
            return null;
        }
        return fromUri(raw, null, null,
                splitBypass(firstNonBlank(environment.get("NO_PROXY"), environment.get("no_proxy"))),
                Source.ENVIRONMENT);
    }

    private static ProxySettings fromUri(String raw, String username, String password,
                                         List<String> bypass, Source source) {
        String normalized = raw.trim().contains("://") ? raw.trim() : "http://" + raw.trim();
        URI uri = URI.create(normalized);
        Type type = typeOf(uri.getScheme());
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("proxy host is required: " + raw);
        }
        String user = username;
        String pass = password;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            if (user == null) {
                user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            }
            if (pass == null && colon >= 0) {
                pass = decode(userInfo.substring(colon + 1));
            }
        }
        int port = uri.getPort() > 0 ? uri.getPort() : type == Type.HTTP ? 80 : 1080;
        return new ProxySettings(type, uri.getHost(), port, blankToNull(user), blankToNull(pass), bypass, source);
    }

    @SuppressWarnings("unchecked")
    private static Selection selectExplicit(Object value, URI targetUri) {
        if (!(value instanceof Map<?, ?> raw)) {
            return value == null ? new Selection(false, null) : new Selection(true, value);
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        boolean websocket = "ws".equalsIgnoreCase(targetUri.getScheme())
                || "wss".equalsIgnoreCase(targetUri.getScheme());
        String[] keys = websocket
                ? new String[]{"webSocketProxy", "websocketProxy", "proxy", "httpProxy"}
                : new String[]{"httpProxy", "proxy"};
        for (String key : keys) {
            if (map.containsKey(key)) {
                return new Selection(true, map.get(key));
            }
        }
        if (map.containsKey("uri") || map.containsKey("url") || map.containsKey("proxyUri")
                || map.containsKey("host") || map.containsKey("proxyHost")) {
            return new Selection(true, map);
        }
        Object transport = map.get("transportProxy");
        return transport == null ? new Selection(false, null) : new Selection(true, transport);
    }

    private boolean shouldBypass(String targetHost) {
        if (targetHost == null) {
            return false;
        }
        String hostValue = targetHost.toLowerCase(Locale.ROOT);
        for (String pattern : nonProxyHosts) {
            String candidate = pattern.trim().toLowerCase(Locale.ROOT);
            if (candidate.equals("*") || candidate.equals(hostValue)) {
                return true;
            }
            if (candidate.startsWith("*.")
                    && (hostValue.equals(candidate.substring(2)) || hostValue.endsWith(candidate.substring(1)))) {
                return true;
            }
            if (candidate.startsWith(".") && hostValue.endsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirect(Object value) {
        if (Boolean.FALSE.equals(value) || value == null) {
            return true;
        }
        if (!(value instanceof String text)) {
            return false;
        }
        String candidate = text.trim().toLowerCase(Locale.ROOT);
        return candidate.isEmpty() || candidate.equals("direct") || candidate.equals("none") || candidate.equals("off");
    }

    private static Type typeOf(String value) {
        return switch (value == null ? "http" : value.toLowerCase(Locale.ROOT)) {
            case "http", "https", "ws", "wss" -> Type.HTTP;
            case "socks4", "socks4a" -> Type.SOCKS4;
            case "socks", "socks5", "socks5h" -> Type.SOCKS5;
            default -> throw new IllegalArgumentException("unsupported proxy scheme: " + value);
        };
    }

    private static boolean isSecure(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme());
    }

    private static List<String> splitBypass(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split("[|,]")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int integer(String value, int defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Returns the proxy protocol. */
    public Type getType() { return type; }

    /** Returns the proxy host. */
    public String getHost() { return host; }

    /** Returns the proxy port. */
    public int getPort() { return port; }

    /** Returns the optional proxy username. */
    public String getUsername() { return username; }

    /** Returns the optional proxy password. */
    public String getPassword() { return password; }

    /** Returns the bypass patterns attached to this proxy. */
    public List<String> getNonProxyHosts() { return nonProxyHosts; }

    /** Returns the configuration source that won precedence. */
    public Source getSource() { return source; }

    /** Returns an unresolved socket address suitable for Netty proxy handlers. */
    public InetSocketAddress toSocketAddress() {
        return InetSocketAddress.createUnresolved(host, port);
    }

    /** Returns the corresponding JDK proxy for {@code java.net.http.HttpClient}. */
    public java.net.Proxy toJavaProxy() {
        java.net.Proxy.Type javaType = type == Type.HTTP ? java.net.Proxy.Type.HTTP : java.net.Proxy.Type.SOCKS;
        return new java.net.Proxy(javaType, toSocketAddress());
    }

    /** Returns a log-safe proxy URI with any user information redacted. */
    public String scrubbedUri() {
        String scheme = type == Type.HTTP ? "http" : type == Type.SOCKS4 ? "socks4" : "socks5";
        return scheme + "://" + (username == null ? "" : "***@") + host + ":" + port;
    }
}
