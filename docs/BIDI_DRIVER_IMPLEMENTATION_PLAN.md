# WebDriver BiDi Implementation Plan

## Outcome

Implement WebDriver BiDi as Karate's cross-browser streaming layer on top of the
existing W3C WebDriver backend. This follows Karate's UI automation roadmap:
CDP remains the Chromium-native backend, classic W3C remains the compatibility
foundation, and BiDi supplies standardized event-driven features without
duplicating the complete driver API.

## Architectural Decision

`BidiDriver` extends `W3cDriver` instead of extending `CdpDriver` or copying its
implementation.

- Classic WebDriver already provides mature navigation primitives, elements,
  actions, cookies, windows, frames, screenshots, uploads, and Grid lifecycle.
- BiDi is negotiated by requesting the standard `webSocketUrl` capability from
  the same WebDriver session.
- Streaming features use a small raw-protocol client built on Karate's WebSocket
  transport. No Selenium BiDi implementation classes are required.
- While interception is active, navigation/reload/history use BiDi so a paused
  network request never deadlocks a synchronous classic WebDriver navigation.
- The public Karate DSL and `Driver` interface stay backend-neutral.

## Configuration Contract

Primary form:

```javascript
{
  type: 'bidi',
  browserName: 'chrome' | 'firefox' | 'MicrosoftEdge',
  webDriverUrl: 'https://grid.example/wd/hub',
  capabilities: { /* browser, provider, and tunnel capabilities */ }
}
```

Compatibility form:

```javascript
{ type: 'geckodriver', bidi: true, webDriverUrl: 'http://localhost:4444' }
```

Optional endpoint controls:

- `bidiWebSocketUrl`: exact endpoint override
- `rewriteWebSocketUrl`: defaults to `true`; replaces a private/loopback
  authority returned by Docker/Grid with the externally reachable WebDriver
  authority while preserving the negotiated BiDi path

## Protocol Layer

The `io.karatelabs.driver.bidi` package contains:

- `BidiClient`: concurrent command correlation, timeouts, event routing, and
  deterministic close behavior
- `BidiCommand`, `BidiResponse`, `BidiEvent`: typed protocol envelopes
- `BidiDialog`: event-driven `Dialog` implementation
- `BidiDriver`: W3C lifecycle plus BiDi navigation, interception, prompt, and
  print operations
- `BidiValue`: standard string/base64 byte values

Command responses are routed immediately by ID. Events are transferred to a
serialized executor, and interception handlers use a separate executor so user
mock logic cannot block WebSocket I/O.

## Proxy and Tunnel Plan

Both connections required by a remote BiDi session must follow the same policy:

1. WebDriver session and command HTTP
2. negotiated BiDi WebSocket

Resolution order:

1. explicit driver config (`httpProxy`, `webSocketProxy`, `proxy`)
2. JVM HTTP/HTTPS/SOCKS properties
3. environment (`HTTPS_PROXY`, `HTTP_PROXY`, `ALL_PROXY`)

Bypass settings (`NO_PROXY`, `http.nonProxyHosts`) and proxy credentials are
honored. Explicit direct mode prevents accidental inheritance from build-agent
environment variables. Netty handlers provide HTTP CONNECT, SOCKS4, and SOCKS5
for WebSockets; the JDK HTTP client uses the corresponding proxy selector and
authenticator.

A provider tunnel is not treated as a transport proxy. Tunnel capabilities pass
through to WebDriver unchanged and make the application reachable from the
remote browser. Karate-to-provider HTTP/WebSocket traffic still uses the proxy
policy above. This separation supports local Docker host exposure, LambdaTest
or Sauce-style tunnels, and enterprise outbound proxies at the same time.

## Feature Coverage

Inherited W3C coverage:

- locators, elements, input/actions, select, submit, frames
- cookies, tabs/windows, dimensions, screenshots
- remote file transfer/upload and session lifecycle
- scenario and feature pooling

BiDi-specific coverage:

- navigation, reload, and history traversal
- request interception and mock fulfillment
- feature-mock delegation with path and query parameters
- prompt opened/closed events and handler callbacks
- PDF printing
- low-level event listeners for future log/network extensions

The first implementation intentionally uses stable W3C operations where BiDi
would add no capability. Future modules such as log streaming, downloads,
authentication challenges, and emulation can be added to `BidiDriver` without
changing the DSL or session model.

## Verification Matrix

Unit tests cover:

- proxy precedence, credentials, bypass, direct mode, HTTP/WS separation
- BiDi response/error/event and byte-value handling
- BiDi option parsing and `webSocketUrl` capability negotiation
- existing WebSocket and W3C regression suites

Real Selenium Grid tests run sequentially for Chrome, Firefox, and Edge:

- a hosted customer page sends a real API request
- `driver.intercept()` delegates it to a Karate feature mock
- the mock matches `/api/customers/{id}`, `status`, and `include`
- the page visibly renders and asserts every returned value
- prompt callbacks and PDF output are verified
- four form scenarios run through a two-session pooled provider
- the provider asserts exactly two Grid sessions were created

Matrix entry points:

- Windows: `scripts/run-bidi-grid-matrix.ps1`
- Unix: `scripts/run-bidi-grid-matrix.sh`

## Completion Criteria

- branch is based on `CPogX/karate` `develop`
- compile and focused unit tests pass
- Chrome, Firefox, and Edge Grid reports are green
- pooled runs use two real browser sessions on all three browsers
- public BiDi/proxy APIs have JavaDoc
- `docs/DRIVER.md` Phase 12 is marked complete with configuration, proxy,
  tunnel, and browser support guidance
- no provider credential is stored in source, tests, reports, or commits
