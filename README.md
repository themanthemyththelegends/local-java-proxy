# local-java-proxy

`Script.java` ("Local Proxy Browser") is a single-file Java program that starts a local HTTP/HTTPS proxy on `127.0.0.1` and launches Chrome or Edge with a dedicated profile configured to use that proxy.

## Requirements

- JDK 17 or newer (uses text blocks, records and switch expressions)
- Windows with Google Chrome or Microsoft Edge installed (auto-detection looks in `LOCALAPPDATA`, `PROGRAMFILES` and `PROGRAMFILES(X86)`; on other systems pass `--browser=PATH`)

## Usage

```
javac -d out Script.java
java -cp out bushscript.Script [URL or search] [options]
```

With no options a Swing GUI opens where you pick the port and choose Chrome or Edge; a log pane shows proxied connections.

| Option | Description |
|---|---|
| `--cli` | Skip the GUI; auto-detect Chrome (falling back to Edge) |
| `--port=NUMBER` | Local proxy port (default `8899`) |
| `--browser=PATH` | Browser executable to launch (CLI mode) |
| `--no-launch` | Only run the proxy, no browser (implies `--cli`); stop with Ctrl+C |
| `--help` | Show help |

The optional positional argument is the start page (default `https://google.com`). A value with a scheme is used as-is, text containing a dot and no spaces gets `https://` prepended, and anything else is sent as a DuckDuckGo search.

Examples:

```
java -cp out bushscript.Script --cli example.com
java -cp out bushscript.Script "java proxy tutorial" --port=9000
java -cp out bushscript.Script --no-launch --port=8899
```

In CLI mode with a browser, the proxy shuts down automatically when the browser window is closed.

## How it works

1. **Startup** – `Script.main` parses the arguments and starts either the GUI (`BushWindow`) or `runCli`.
2. **Proxy server** – `ProxyServer` binds a `ServerSocket` to `127.0.0.1` only (not reachable from the network). An acceptor thread hands each connection to a worker thread pool. Request headers are read up to a size limit, with a 30 second timeout on the initial request.
3. **HTTPS (`CONNECT`)** – The proxy opens a TCP connection to the requested host:port, replies `200 Connection Established`, then relays bytes in both directions. Traffic is tunneled and never decrypted, so page contents cannot be inspected or modified.
4. **Plain HTTP** – The absolute-form request line is rewritten to origin-form (path and query), `Proxy-Connection` and `Proxy-Authorization` headers are dropped, the request is sent to the destination (from the URL or `Host` header, default port 80), and the rest of the connection is relayed both ways. Failed upstream connections return `502`.
5. **Browser launch** – The browser starts with an isolated profile at `~/.Bush-local-browser/<chrome|edge>` and the flags `--proxy-server=http://127.0.0.1:<port>`, `--proxy-bypass-list=<-loopback>` (loopback addresses also go through the proxy), `--no-first-run`, `--no-default-browser-check` and `--new-window`. Your normal browser profile is untouched.
6. **GUI** – `BushWindow` validates the port (1–65535), starts or restarts the proxy if needed, launches the selected browser, and shows live status and logs.