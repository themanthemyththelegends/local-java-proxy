package bushscript;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bush Local Browser.
 * A local HTTP proxy launcher with a modern Swing interface.
 * HTTPS traffic is tunneled without decryption.
 */
public final class Script {
    private static final int DEFAULT_PORT = 8899;
    private static final String DEFAULT_URL = "https://google.com";

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            printHelp();
            return;
        }

        int port = DEFAULT_PORT;
        String requestedBrowser = null;
        String startUrl = DEFAULT_URL;
        boolean noLaunch = false;
        boolean cli = false;

        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--browser=")) {
                requestedBrowser = unquote(arg.substring("--browser=".length()));
            } else if (arg.equals("--no-launch")) {
                noLaunch = true;
                cli = true;
            } else if (arg.equals("--cli")) {
                cli = true;
            } else if (!arg.startsWith("--")) {
                startUrl = normalizeUrl(arg);
            }
        }

        if (cli) {
            runCli(port, requestedBrowser, startUrl, noLaunch);
        } else {
            final int guiPort = port;
            final String guiUrl = startUrl;
            SwingUtilities.invokeLater(() -> new BushWindow(guiPort, guiUrl).setVisible(true));
        }
    }

    private static void runCli(int port, String requestedBrowser, String startUrl,
                               boolean noLaunch) throws Exception {
        ProxyServer proxy = new ProxyServer(port, System.out::println);
        proxy.start();
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::close, "Bush-shutdown"));

        System.out.println("Bush Local Browser");
        System.out.println("Proxy:   http://127.0.0.1:" + proxy.port());
        System.out.println("Privacy: HTTPS is tunneled without decryption");

        if (!noLaunch) {
            Path exe = requestedBrowser != null
                    ? requireFile(requestedBrowser)
                    : Browser.findChromiumAuto();
            Browser kind = Browser.guessFromPath(exe);
            Process process = launchBrowser(kind, exe, proxy.port(), startUrl);
            System.out.println("Browser: " + exe);
            System.out.println("The proxy will automatically stop when you close the browser...");
            process.waitFor();
            System.out.println("Browser closed. Shutting down.");
            proxy.close();
        } else {
            System.out.println("Browser launch disabled; press Ctrl+C to stop.");
            new CountDownLatch(1).await();
        }
    }

    private static void printHelp() {
        System.out.println("""
                Bush Local Browser

                Usage:
                  javac -d out Script.java
                  java -cp out bushscript.Script [URL or search] [options]

                With no options a GUI opens where you choose Chrome or Edge.

                Options:
                  --cli               Skip the GUI (Chrome/Edge auto-detected)
                  --port=NUMBER       Local proxy port (default 8899)
                  --browser=PATH      Browser executable (CLI mode)
                  --no-launch         Start only the local proxy (CLI mode)
                  --help              Show this help

                The proxy listens only on 127.0.0.1. HTTPS uses CONNECT tunneling;
                Bush cannot inspect or modify encrypted page contents.
                """);
    }

    enum Browser {
        CHROME("Chrome", "C", new Color(0x4285F4), "chrome"),
        EDGE("Microsoft Edge", "E", new Color(0x0FA5A5), "edge");

        final String label;
        final String letter;
        final Color accent;
        final String profileName;

        Browser(String label, String letter, Color accent, String profileName) {
            this.label = label;
            this.letter = letter;
            this.accent = accent;
            this.profileName = profileName;
        }

        Path find() throws IOException {
            List<Path> candidates = new ArrayList<>();
            String local = System.getenv("LOCALAPPDATA");
            String pf = System.getenv("PROGRAMFILES");
            String pf86 = System.getenv("PROGRAMFILES(X86)");

            if (this == CHROME) {
                addCandidate(candidates, local, "Google", "Chrome", "Application", "chrome.exe");
                addCandidate(candidates, pf, "Google", "Chrome", "Application", "chrome.exe");
                addCandidate(candidates, pf86, "Google", "Chrome", "Application", "chrome.exe");
            } else {
                addCandidate(candidates, pf86, "Microsoft", "Edge", "Application", "msedge.exe");
                addCandidate(candidates, pf, "Microsoft", "Edge", "Application", "msedge.exe");
                addCandidate(candidates, local, "Microsoft", "Edge", "Application", "msedge.exe");
            }

            return candidates.stream().filter(Files::isRegularFile).findFirst()
                    .orElseThrow(() -> new IOException(label + " was not found on this computer."));
        }

        static Path findChromiumAuto() throws IOException {
            try {
                return CHROME.find();
            } catch (IOException ignored) {
                return EDGE.find();
            }
        }

        static Browser guessFromPath(Path path) {
            return path.getFileName().toString().toLowerCase(Locale.ROOT).contains("msedge")
                    ? EDGE : CHROME;
        }
    }

    private static void addCandidate(List<Path> candidates, String root, String... parts) {
        if (root == null || root.isBlank()) return;
        Path path = Path.of(root);
        for (String part : parts) path = path.resolve(part);
        candidates.add(path);
    }

    private static Path requireFile(String requested) throws IOException {
        Path path = Path.of(requested);
        if (Files.isRegularFile(path)) return path;
        throw new IOException("Browser executable not found: " + requested);
    }

    private static Process launchBrowser(Browser kind, Path exe, int port, String url)
            throws IOException {
        Path root = Path.of(System.getProperty("user.home"), ".Bush-local-browser");
        Path profile = root.resolve(kind.profileName);
        Files.createDirectories(profile);

        return new ProcessBuilder(
                exe.toString(),
                "--user-data-dir=" + profile,
                "--proxy-server=http://127.0.0.1:" + port,
                "--proxy-bypass-list=<-loopback>",
                "--no-first-run",
                "--no-default-browser-check",
                "--new-window",
                url)
                .inheritIO()
                .start();
    }

    private static String normalizeUrl(String value) {
        String trimmed = unquote(value.trim());
        if (trimmed.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) return trimmed;
        if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://" + trimmed;
        return "https://duckduckgo.com/?q="
                + java.net.URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static final class ProxyServer {
        private static final int MAX_HEADER_BYTES = 64 * 1024;
        private static final int CONNECT_TIMEOUT_MS = 15_000;
        private static final AtomicLong CONNECTIONS = new AtomicLong();

        private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "Bush-proxy-worker");
            thread.setDaemon(true);
            return thread;
        });
        private final ServerSocket server;
        private final Consumer<String> log;

        ProxyServer(int port, Consumer<String> log) throws IOException {
            this.log = log;
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", port));
        }

        int port() { return server.getLocalPort(); }
        boolean isRunning() { return !server.isClosed(); }

        void start() {
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        client.setTcpNoDelay(true);
                        workers.execute(() -> handle(client));
                    } catch (IOException error) {
                        if (!server.isClosed()) log.accept("Proxy accept error: " + error.getMessage());
                    }
                }
            }, "Bush-proxy-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void close() {
            try { server.close(); } catch (IOException ignored) { }
            workers.shutdownNow();
        }

        private void handle(Socket client) {
            long id = CONNECTIONS.incrementAndGet();
            try (client) {
                client.setSoTimeout(30_000);
                InputStream clientIn = client.getInputStream();
                OutputStream clientOut = client.getOutputStream();
                byte[] headerBytes = readHeader(clientIn);
                if (headerBytes.length == 0) return;

                String header = new String(headerBytes, StandardCharsets.ISO_8859_1);
                String[] lines = header.split("\\r?\\n");
                String[] request = lines[0].split(" ", 3);
                if (request.length < 2) {
                    sendError(clientOut, 400, "Bad Request");
                    return;
                }

                String method = request[0].toUpperCase(Locale.ROOT);
                if ("CONNECT".equals(method)) {
                    Target target = parseAuthority(request[1], 443);
                    log.accept("[" + id + "] CONNECT " + target.host + ":" + target.port);
                    tunnelConnect(clientIn, clientOut, target, client);
                } else {
                    Target target = targetForHttp(request[1], lines);
                    log.accept("[" + id + "] " + method + " " + target.host + ":" + target.port);
                    forwardHttp(clientIn, clientOut, header, request, target, client);
                }
            } catch (Exception error) {
                log.accept("[" + id + "] " + error.getMessage());
            }
        }

        private void tunnelConnect(InputStream clientIn, OutputStream clientOut, Target target, Socket client)
                throws IOException {
            try (Socket upstream = connect(target)) {
                clientOut.write(("HTTP/1.1 200 Connection Established\r\n"
                        + "Proxy-Agent: BushLocal/1.0\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                clientOut.flush();
                relayBothWays(clientIn, clientOut, upstream, client);
            } catch (IOException error) {
                sendError(clientOut, 502, "Unable to connect");
                throw error;
            }
        }

        private void forwardHttp(InputStream clientIn, OutputStream clientOut,
                                 String originalHeader, String[] request, Target target, Socket client)
                throws IOException {
            try (Socket upstream = connect(target)) {
                OutputStream upstreamOut = upstream.getOutputStream();
                upstreamOut.write(rewriteHttpHeader(originalHeader, request)
                        .getBytes(StandardCharsets.ISO_8859_1));
                upstreamOut.flush();
                relayBothWays(clientIn, clientOut, upstream, client);
            } catch (IOException error) {
                sendError(clientOut, 502, "Unable to connect");
                throw error;
            }
        }

        private void relayBothWays(InputStream clientIn, OutputStream clientOut, Socket upstream, Socket client)
                throws IOException {
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);
            InputStream upstreamIn = upstream.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();
            workers.execute(() -> copyAndHalfClose(clientIn, upstreamOut, upstream));
            copyAndHalfClose(upstreamIn, clientOut, client);
        }

        private static void copyAndHalfClose(InputStream in, OutputStream out, Socket outputSocket) {
            byte[] buffer = new byte[32 * 1024];
            try {
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, count);
                    out.flush();
                }
            } catch (IOException ignored) {
                // Interrupted / closing either side
            } finally {
                if (outputSocket != null && !outputSocket.isClosed()) {
                    try { outputSocket.shutdownOutput(); } catch (IOException ignored) { }
                }
            }
        }

        private static Socket connect(Target target) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
            return socket;
        }

        private static byte[] readHeader(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int state = 0;
            while (bytes.size() < MAX_HEADER_BYTES) {
                int value = input.read();
                if (value < 0) break;
                bytes.write(value);
                state = switch (state) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : 0;
                    case 2 -> value == '\r' ? 3 : 0;
                    case 3 -> value == '\n' ? 4 : 0;
                    default -> 4;
                };
                if (state == 4) return bytes.toByteArray();
            }
            if (bytes.size() >= MAX_HEADER_BYTES) throw new IOException("Request headers are too large");
            return bytes.toByteArray();
        }

        private static Target targetForHttp(String requestTarget, String[] lines) throws IOException {
            try {
                URI uri = new URI(requestTarget);
                if (uri.getHost() != null) {
                    int port = uri.getPort() > 0 ? uri.getPort() : 80;
                    return new Target(uri.getHost(), port);
                }
            } catch (URISyntaxException ignored) { }

            for (String line : lines) {
                if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                    return parseAuthority(line.substring(5).trim(), 80);
                }
            }
            throw new IOException("HTTP request has no destination host");
        }

        private static String rewriteHttpHeader(String header, String[] request) {
            String target = request[1];
            try {
                URI uri = new URI(target);
                if (uri.isAbsolute()) {
                    String path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    target = path;
                }
            } catch (URISyntaxException ignored) { }

            StringBuilder result = new StringBuilder();
            result.append(request[0]).append(' ').append(target);
            if (request.length == 3) result.append(' ').append(request[2]);
            result.append("\r\n");

            String[] lines = header.split("\\r?\\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                if (!line.regionMatches(true, 0, "Proxy-Connection:", 0, 17)
                        && !line.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) {
                    result.append(line).append("\r\n");
                }
            }
            result.append("\r\n");
            return result.toString();
        }

        private static Target parseAuthority(String authority, int defaultPort) throws IOException {
            String value = authority.trim();
            if (value.startsWith("[")) {
                int end = value.indexOf(']');
                if (end < 0) throw new IOException("Invalid IPv6 destination");
                String host = value.substring(1, end);
                int port = end + 1 < value.length() && value.charAt(end + 1) == ':'
                        ? parsePort(value.substring(end + 2)) : defaultPort;
                return new Target(host, port);
            }

            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(':') == colon) {
                return new Target(value.substring(0, colon), parsePort(value.substring(colon + 1)));
            }
            if (value.isBlank()) throw new IOException("Empty destination");
            return new Target(value, defaultPort);
        }

        private static int parsePort(String value) throws IOException {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65_535) throw new NumberFormatException();
                return port;
            } catch (NumberFormatException error) {
                throw new IOException("Invalid destination port");
            }
        }

        private static void sendError(OutputStream output, int status, String message) {
            try {
                String body = "Bush Proxy: " + message + "\n";
                String response = "HTTP/1.1 " + status + " " + message + "\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                        + "Connection: close\r\n\r\n" + body;
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException ignored) { }
        }

        private record Target(String host, int port) { }
    }

    static final class BushWindow extends JFrame {
        private static final Color BG = new Color(0x0F1115);
        private static final Color PANEL = new Color(0x181B22);
        private static final Color PANEL_HOVER = new Color(0x222833);
        private static final Color FIELD = new Color(0x12151B);
        private static final Color BORDER = new Color(0x2A2F3A);
        private static final Color TEXT = new Color(0xE8EAF0);
        private static final Color MUTED = new Color(0x8A90A0);
        private static final Color ACCENT = new Color(0x5B8CFF);
        private static final Color OK = new Color(0x3DD68C);
        private static final Color OFF = new Color(0x5A6070);

        private final List<BrowserCard> cards = new ArrayList<>();
        private Browser selected = Browser.CHROME;
        private final JTextField urlField = new JTextField();
        private final JTextField portField = new JTextField();
        private final JCheckBox autoStop = new JCheckBox("Stop proxy when all browsers close");
        private final JTextArea logArea = new JTextArea();
        private final StatusDot dot = new StatusDot();
        private final JLabel statusLabel = new JLabel("Proxy stopped");
        private final RoundButton launchButton = new RoundButton("Launch with proxy");
        private final RoundButton stopButton = new RoundButton("Stop proxy");
        private JButton maximizeButton;
        private ProxyServer proxy;
        private final AtomicInteger openBrowsers = new AtomicInteger();

        private Point dragStart;
        private Point windowStart;
        private boolean maximized;
        private Rectangle restoreBounds;

        BushWindow(int port, String url) {
            super("Bush Local Browser");
            setUndecorated(true);
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(680, 760);
            setMinimumSize(new Dimension(500, 560));
            centerOnScreen();
            getContentPane().setBackground(BG);

            // Top section for options & inputs
            JPanel topSection = new JPanel();
            topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
            topSection.setBackground(BG);
            topSection.setAlignmentX(Component.LEFT_ALIGNMENT);

            topSection.add(header());
            topSection.add(Box.createVerticalStrut(20));
            topSection.add(sectionLabel("CHOOSE A BROWSER"));
            topSection.add(Box.createVerticalStrut(10));
            topSection.add(cardRow());
            topSection.add(Box.createVerticalStrut(20));

            topSection.add(sectionLabel("START PAGE"));
            topSection.add(Box.createVerticalStrut(8));
            styleField(urlField);
            urlField.setText(url);
            urlField.addActionListener(e -> launch());
            urlField.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { urlField.selectAll(); }
            });
            topSection.add(left(urlField));
            topSection.add(Box.createVerticalStrut(16));

            topSection.add(sectionLabel("LOCAL PROXY PORT"));
            topSection.add(Box.createVerticalStrut(8));
            styleField(portField);
            portField.setText(String.valueOf(port));
            portField.setMaximumSize(new Dimension(160, 42));
            portField.setPreferredSize(new Dimension(160, 42));
            portField.addActionListener(e -> launch());
            portField.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { portField.selectAll(); }
            });
            // Digits-only filter for the port field
            ((AbstractDocument) portField.getDocument()).setDocumentFilter(new DocumentFilter() {
                @Override
                public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                        throws BadLocationException {
                    if (string != null && string.matches("\\d*")) super.insertString(fb, offset, string, attr);
                }
                @Override
                public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                        throws BadLocationException {
                    if (text != null && text.matches("\\d*")) super.replace(fb, offset, length, text, attrs);
                }
            });
            topSection.add(left(portField));
            topSection.add(Box.createVerticalStrut(12));

            autoStop.setOpaque(false);
            autoStop.setForeground(MUTED);
            autoStop.setFocusPainted(false);
            autoStop.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            autoStop.setSelected(true);
            topSection.add(left(autoStop));
            topSection.add(Box.createVerticalStrut(16));

            JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
            buttons.setOpaque(false);
            buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
            launchButton.setColors(ACCENT, ACCENT.brighter());
            stopButton.setColors(PANEL, PANEL_HOVER);
            stopButton.setEnabled(false);
            launchButton.addActionListener(e -> launch());
            stopButton.addActionListener(e -> stopProxy());
            buttons.add(launchButton);
            buttons.add(stopButton);
            topSection.add(buttons);
            topSection.add(Box.createVerticalStrut(14));

            JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            status.setOpaque(false);
            status.setAlignmentX(Component.LEFT_ALIGNMENT);
            statusLabel.setForeground(MUTED);
            statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            status.add(dot);
            status.add(statusLabel);
            topSection.add(status);
            topSection.add(Box.createVerticalStrut(14));

            JPanel logHeader = new JPanel(new BorderLayout());
            logHeader.setOpaque(false);
            logHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            logHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
            logHeader.add(sectionLabel("PROXY ACTIVITY LOG"), BorderLayout.WEST);

            JButton clearBtn = new JButton("Clear Log");
            clearBtn.setOpaque(false);
            clearBtn.setContentAreaFilled(false);
            clearBtn.setBorderPainted(false);
            clearBtn.setFocusPainted(false);
            clearBtn.setForeground(MUTED);
            clearBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            clearBtn.addActionListener(e -> logArea.setText(""));
            clearBtn.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { clearBtn.setForeground(TEXT); }
                @Override public void mouseExited(MouseEvent e) { clearBtn.setForeground(MUTED); }
            });
            logHeader.add(clearBtn, BorderLayout.EAST);
            topSection.add(logHeader);
            topSection.add(Box.createVerticalStrut(8));

            // Log Area inside an expanding center scroll pane
            logArea.setEditable(false);
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
            logArea.setBackground(FIELD);
            logArea.setForeground(new Color(0xA8B0C0));
            logArea.setCaretColor(TEXT);
            logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
            logArea.setBorder(new EmptyBorder(10, 12, 10, 12));

            // Context menu for the log area
            JPopupMenu menu = new JPopupMenu();
            JMenuItem copyItem = new JMenuItem("Copy Selection");
            copyItem.addActionListener(e -> logArea.copy());
            JMenuItem copyAllItem = new JMenuItem("Copy All");
            copyAllItem.addActionListener(e -> {
                String text = logArea.getText();
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(text), null);
            });
            JMenuItem clearItem = new JMenuItem("Clear Log");
            clearItem.addActionListener(e -> logArea.setText(""));
            menu.add(copyItem);
            menu.add(copyAllItem);
            menu.addSeparator();
            menu.add(clearItem);
            logArea.setComponentPopupMenu(menu);

            JScrollPane scroll = new JScrollPane(logArea);
            scroll.setBorder(BorderFactory.createLineBorder(BORDER));
            scroll.getViewport().setBackground(FIELD);
            scroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            scroll.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

            // Main layout assembly: Top section + flexible center log
            JPanel centerContent = new JPanel(new BorderLayout(0, 0));
            centerContent.setBackground(BG);
            centerContent.setBorder(new EmptyBorder(20, 26, 20, 26));
            centerContent.add(topSection, BorderLayout.NORTH);
            centerContent.add(scroll, BorderLayout.CENTER);

            JPanel windowRoot = new JPanel(new BorderLayout());
            windowRoot.setBackground(BG);
            windowRoot.setBorder(BorderFactory.createLineBorder(BORDER));
            windowRoot.add(createTitleBar(), BorderLayout.NORTH);
            windowRoot.add(centerContent, BorderLayout.CENTER);
            setContentPane(windowRoot);

            // Window controls & shortcuts
            getRootPane().registerKeyboardAction(
                    e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);

            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    if (proxy != null) proxy.close();
                }
            });

            enableResizing();

            for (BrowserCard card : cards) {
                try { card.browser.find(); }
                catch (IOException missing) { card.setInstalled(false); }
            }
            appendLog("Ready. Select a browser and click Launch.");
        }

        private void centerOnScreen() {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            int usableX = bounds.x + insets.left;
            int usableY = bounds.y + insets.top;
            int usableW = bounds.width - insets.left - insets.right;
            int usableH = bounds.height - insets.top - insets.bottom;
            setLocation(usableX + (usableW - getWidth()) / 2,
                    usableY + (usableH - getHeight()) / 2);
        }

        private JPanel createTitleBar() {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(new Color(0x14171D));
            bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
            bar.setPreferredSize(new Dimension(100, 44));

            JLabel title = new JLabel("  BUSH  /  LOCAL BROWSER");
            title.setForeground(TEXT);
            title.setFont(new Font("Segoe UI", Font.BOLD, 12));
            bar.add(title, BorderLayout.CENTER);

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            controls.setOpaque(false);

            JButton minimize = windowButton("—", false);
            maximizeButton = windowButton("□", false);
            JButton close = windowButton("×", true);

            minimize.addActionListener(e -> setState(Frame.ICONIFIED));
            maximizeButton.addActionListener(e -> toggleMaximize());
            close.addActionListener(e -> dispatchEvent(
                    new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

            controls.add(minimize);
            controls.add(maximizeButton);
            controls.add(close);
            bar.add(controls, BorderLayout.EAST);

            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && !maximized) {
                        dragStart = e.getLocationOnScreen();
                        windowStart = getLocation();
                    } else if (SwingUtilities.isLeftMouseButton(e) && maximized) {
                        Point p = e.getLocationOnScreen();
                        int oldW = getWidth();
                        toggleMaximize();
                        double ratio = (double) e.getX() / Math.max(1, oldW);
                        int newX = (int) (p.x - ratio * getWidth());
                        int newY = p.y - e.getY();
                        setLocation(newX, newY);
                        dragStart = p;
                        windowStart = getLocation();
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart != null && windowStart != null && !maximized) {
                        Point p = e.getLocationOnScreen();
                        setLocation(windowStart.x + p.x - dragStart.x,
                                windowStart.y + p.y - dragStart.y);
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    dragStart = null;
                    windowStart = null;
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        toggleMaximize();
                    }
                }
            };
            bar.addMouseListener(drag);
            bar.addMouseMotionListener(drag);
            title.addMouseListener(drag);
            title.addMouseMotionListener(drag);
            return bar;
        }

        private void toggleMaximize() {
            if (maximized) {
                maximized = false;
                maximizeButton.setText("□");
                if (restoreBounds != null) {
                    setBounds(restoreBounds);
                } else {
                    setSize(680, 760);
                    centerOnScreen();
                }
            } else {
                restoreBounds = getBounds();
                GraphicsConfiguration gc = getGraphicsConfiguration();
                Rectangle bounds = gc.getBounds();
                Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                setBounds(bounds.x + insets.left, bounds.y + insets.top,
                        bounds.width - insets.left - insets.right,
                        bounds.height - insets.top - insets.bottom);
                maximized = true;
                maximizeButton.setText("❐");
            }
            revalidate();
            repaint();
        }

        private void enableResizing() {
            final int BORDER_DRAG = 7;
            AWTEventListener resizeListener = new AWTEventListener() {
                private int cursorType = Cursor.DEFAULT_CURSOR;
                private Point dragStartPoint = null;
                private Rectangle dragStartBounds = null;

                @Override
                public void eventDispatched(AWTEvent event) {
                    if (!(event instanceof MouseEvent me)) return;
                    Object src = me.getSource();
                    if (!(src instanceof Component comp)) return;
                    if (SwingUtilities.getWindowAncestor(comp) != BushWindow.this
                            && comp != BushWindow.this) {
                        return;
                    }

                    if (maximized) {
                        if (cursorType != Cursor.DEFAULT_CURSOR) {
                            setCursor(Cursor.getDefaultCursor());
                            cursorType = Cursor.DEFAULT_CURSOR;
                        }
                        return;
                    }

                    Point p = me.getLocationOnScreen();
                    Rectangle b = getBounds();
                    int leftDist = p.x - b.x;
                    int rightDist = (b.x + b.width) - p.x;
                    int topDist = p.y - b.y;
                    int bottomDist = (b.y + b.height) - p.y;

                    if (me.getID() == MouseEvent.MOUSE_MOVED) {
                        if (leftDist < 0 || rightDist < 0 || topDist < 0 || bottomDist < 0) {
                            if (cursorType != Cursor.DEFAULT_CURSOR) {
                                setCursor(Cursor.getDefaultCursor());
                                cursorType = Cursor.DEFAULT_CURSOR;
                            }
                            return;
                        }

                        boolean west = leftDist <= BORDER_DRAG;
                        boolean east = rightDist <= BORDER_DRAG;
                        boolean north = topDist <= BORDER_DRAG;
                        boolean south = bottomDist <= BORDER_DRAG;

                        int newCursor;
                        if (north && west) newCursor = Cursor.NW_RESIZE_CURSOR;
                        else if (north && east) newCursor = Cursor.NE_RESIZE_CURSOR;
                        else if (south && west) newCursor = Cursor.SW_RESIZE_CURSOR;
                        else if (south && east) newCursor = Cursor.SE_RESIZE_CURSOR;
                        else if (north) newCursor = Cursor.N_RESIZE_CURSOR;
                        else if (south) newCursor = Cursor.S_RESIZE_CURSOR;
                        else if (west) newCursor = Cursor.W_RESIZE_CURSOR;
                        else if (east) newCursor = Cursor.E_RESIZE_CURSOR;
                        else newCursor = Cursor.DEFAULT_CURSOR;

                        if (newCursor != cursorType) {
                            cursorType = newCursor;
                            setCursor(Cursor.getPredefinedCursor(newCursor));
                        }
                    } else if (me.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(me)) {
                        if (cursorType != Cursor.DEFAULT_CURSOR) {
                            dragStartPoint = p;
                            dragStartBounds = new Rectangle(b);
                            me.consume();
                        }
                    } else if (me.getID() == MouseEvent.MOUSE_DRAGGED && dragStartPoint != null) {
                        int dx = p.x - dragStartPoint.x;
                        int dy = p.y - dragStartPoint.y;

                        int x = dragStartBounds.x;
                        int y = dragStartBounds.y;
                        int w = dragStartBounds.width;
                        int h = dragStartBounds.height;
                        Dimension min = getMinimumSize();

                        if (cursorType == Cursor.E_RESIZE_CURSOR || cursorType == Cursor.NE_RESIZE_CURSOR
                                || cursorType == Cursor.SE_RESIZE_CURSOR) {
                            w = Math.max(min.width, dragStartBounds.width + dx);
                        }
                        if (cursorType == Cursor.S_RESIZE_CURSOR || cursorType == Cursor.SW_RESIZE_CURSOR
                                || cursorType == Cursor.SE_RESIZE_CURSOR) {
                            h = Math.max(min.height, dragStartBounds.height + dy);
                        }
                        if (cursorType == Cursor.W_RESIZE_CURSOR || cursorType == Cursor.NW_RESIZE_CURSOR
                                || cursorType == Cursor.SW_RESIZE_CURSOR) {
                            int newW = dragStartBounds.width - dx;
                            if (newW >= min.width) {
                                x = dragStartBounds.x + dx;
                                w = newW;
                            } else {
                                x = dragStartBounds.x + (dragStartBounds.width - min.width);
                                w = min.width;
                            }
                        }
                        if (cursorType == Cursor.N_RESIZE_CURSOR || cursorType == Cursor.NW_RESIZE_CURSOR
                                || cursorType == Cursor.NE_RESIZE_CURSOR) {
                            int newH = dragStartBounds.height - dy;
                            if (newH >= min.height) {
                                y = dragStartBounds.y + dy;
                                h = newH;
                            } else {
                                y = dragStartBounds.y + (dragStartBounds.height - min.height);
                                h = min.height;
                            }
                        }

                        setBounds(x, y, w, h);
                        revalidate();
                        repaint();
                        me.consume();
                    } else if (me.getID() == MouseEvent.MOUSE_RELEASED) {
                        if (dragStartPoint != null) {
                            dragStartPoint = null;
                            dragStartBounds = null;
                            me.consume();
                        }
                    }
                }
            };

            Toolkit.getDefaultToolkit().addAWTEventListener(resizeListener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);

            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(resizeListener);
                }
            });
        }

        private JButton windowButton(String text, boolean close) {
            JButton button = new JButton(text);
            Color normal = new Color(0x14171D);
            button.setPreferredSize(new Dimension(46, 43));
            button.setForeground(TEXT);
            button.setBackground(normal);
            button.setFont(new Font("Segoe UI", Font.PLAIN, 17));
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setContentAreaFilled(true);
            button.setOpaque(true);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    button.setBackground(close ? new Color(0xC42B1C) : new Color(0x303641));
                }
                @Override public void mouseExited(MouseEvent e) {
                    button.setBackground(normal);
                }
            });
            return button;
        }

        private JComponent header() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(false);
            JLabel title = new JLabel("Bush Local Browser");
            title.setForeground(TEXT);
            title.setFont(new Font("Segoe UI", Font.BOLD, 26));
            JLabel sub = new JLabel("A cleaner way to browse through your local proxy.");
            sub.setForeground(MUTED);
            sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            panel.add(left(title));
            panel.add(Box.createVerticalStrut(5));
            panel.add(left(sub));
            return left(panel);
        }

        private JComponent cardRow() {
            JPanel row = new JPanel(new GridLayout(1, 2, 14, 0));
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 126));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (Browser browser : Browser.values()) {
                BrowserCard card = new BrowserCard(browser);
                cards.add(card);
                row.add(card);
            }
            cards.get(0).setSelectedCard(true);
            return row;
        }

        private JLabel sectionLabel(String text) {
            JLabel label = new JLabel(text);
            label.setForeground(MUTED);
            label.setFont(new Font("Segoe UI", Font.BOLD, 11));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            return label;
        }

        private static JComponent left(JComponent component) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            return component;
        }

        private void styleField(JTextField field) {
            field.setBackground(FIELD);
            field.setForeground(TEXT);
            field.setCaretColor(TEXT);
            field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER),
                    new EmptyBorder(8, 12, 8, 12)));
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        }

        private void launch() {
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65_535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                error("Enter a port between 1 and 65535.");
                return;
            }

            Path exe;
            try {
                exe = selected.find();
            } catch (IOException e) {
                error(e.getMessage());
                return;
            }

            try {
                if (proxy == null || !proxy.isRunning() || proxy.port() != port) {
                    if (proxy != null) proxy.close();
                    proxy = new ProxyServer(port, this::appendLog);
                    proxy.start();
                    appendLog("Proxy listening on 127.0.0.1:" + proxy.port());
                }
            } catch (IOException e) {
                error("Could not start the proxy on port " + port + ":\n" + e.getMessage());
                return;
            }

            String url = normalizeUrl(urlField.getText().isBlank()
                    ? DEFAULT_URL : urlField.getText());
            Process process;
            try {
                process = launchBrowser(selected, exe, proxy.port(), url);
            } catch (IOException e) {
                error("Could not launch " + selected.label + ":\n" + e.getMessage());
                return;
            }

            openBrowsers.incrementAndGet();
            setProxyState(true);
            appendLog("Launched " + selected.label + " through the proxy.");

            Thread watcher = new Thread(() -> {
                try { process.waitFor(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                SwingUtilities.invokeLater(() -> {
                    int remaining = openBrowsers.decrementAndGet();
                    appendLog("A browser process closed.");
                    if (remaining <= 0 && autoStop.isSelected()) stopProxy();
                });
            }, "Bush-browser-watcher");
            watcher.setDaemon(true);
            watcher.start();
        }

        private void stopProxy() {
            if (proxy != null) {
                proxy.close();
                proxy = null;
                appendLog("Proxy stopped.");
            }
            openBrowsers.set(0);
            setProxyState(false);
        }

        private void setProxyState(boolean running) {
            dot.setOn(running);
            statusLabel.setText(running && proxy != null
                    ? "Proxy running on 127.0.0.1:" + proxy.port()
                    : "Proxy stopped");
            stopButton.setEnabled(running);
        }

        private void error(String message) {
            JOptionPane.showMessageDialog(this, message, "Bush Local Browser",
                    JOptionPane.ERROR_MESSAGE);
        }

        private void appendLog(String line) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(line + "\n");
                int lines = logArea.getLineCount();
                if (lines > 500) {
                    try {
                        logArea.replaceRange("", 0, logArea.getLineEndOffset(lines - 400));
                    } catch (BadLocationException ignored) { }
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }

        private final class BrowserCard extends JPanel {
            final Browser browser;
            private boolean isSelected;
            private boolean hover;
            private boolean installed = true;
            private Image logo;

            BrowserCard(Browser browser) {
                this.browser = browser;
                logo = loadLogo(browser);
                setOpaque(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setPreferredSize(new Dimension(160, 120));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        if (!installed) {
                            appendLog("Note: " + browser.label + " was not detected in typical installation directories.");
                        }
                        selected = browser;
                        for (BrowserCard card : cards) {
                            card.setSelectedCard(card == BrowserCard.this);
                        }
                    }
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }

            void setSelectedCard(boolean value) {
                isSelected = value;
                repaint();
            }

            void setInstalled(boolean value) {
                installed = value;
                setToolTipText(value ? null : browser.label + " was not found on this computer");
                repaint();
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                int w = getWidth(), h = getHeight();
                g2.setColor(hover ? PANEL_HOVER : PANEL);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 22, 22);
                g2.setStroke(new BasicStroke(isSelected ? 2.5f : 1f));
                g2.setColor(isSelected ? browser.accent : BORDER);
                g2.drawRoundRect(1, 1, w - 3, h - 3, 22, 22);

                int d = 48;
                int cx = (w - d) / 2;
                FontMetrics fm;
                if (logo != null) {
                    if (!installed) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                    g2.drawImage(logo, cx, 12, d, d, this);
                    g2.setComposite(AlphaComposite.SrcOver);
                } else {
                    g2.setColor(installed ? browser.accent : OFF);
                    g2.fillOval(cx, 14, d, d);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 22));
                    fm = g2.getFontMetrics();
                    g2.drawString(browser.letter, cx + (d - fm.stringWidth(browser.letter)) / 2,
                            14 + (d + fm.getAscent() - fm.getDescent()) / 2);
                }

                g2.setColor(installed ? TEXT : MUTED);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 14));
                fm = g2.getFontMetrics();
                g2.drawString(browser.label, (w - fm.stringWidth(browser.label)) / 2, 82);

                if (!installed) {
                    g2.setColor(MUTED);
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                    fm = g2.getFontMetrics();
                    String note = "not installed";
                    g2.drawString(note, (w - fm.stringWidth(note)) / 2, 100);
                }
                g2.dispose();
            }
        }

        private static Image loadLogo(Browser browser) {
            try {
                Path custom = Path.of(System.getProperty("user.home"), ".Bush-local-browser",
                        "logos", browser.profileName + ".png");
                if (Files.isRegularFile(custom)) {
                    Image img = javax.imageio.ImageIO.read(custom.toFile());
                    if (img != null) return img;
                }
            } catch (Exception ignored) { }

            try {
                Icon icon = javax.swing.filechooser.FileSystemView.getFileSystemView()
                        .getSystemIcon(browser.find().toFile());
                if (icon != null && icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
                    int width = Math.max(128, icon.getIconWidth() * 4);
                    int height = Math.max(128, icon.getIconHeight() * 4);
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                            width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.scale((double) width / icon.getIconWidth(),
                            (double) height / icon.getIconHeight());
                    icon.paintIcon(null, g, 0, 0);
                    g.dispose();
                    return image;
                }
            } catch (Exception ignored) { }
            return null;
        }

        private static final class DarkScrollBarUI extends BasicScrollBarUI {
            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = new Color(0x2E3440);
                this.trackColor = FIELD;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) { return createZeroButton(); }

            @Override
            protected JButton createIncreaseButton(int orientation) { return createZeroButton(); }

            private JButton createZeroButton() {
                JButton btn = new JButton();
                btn.setPreferredSize(new Dimension(0, 0));
                return btn;
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isThumbRollover() ? new Color(0x454E60) : thumbColor);
                g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2,
                        thumbBounds.width - 4, thumbBounds.height - 4, 8, 8);
                g2.dispose();
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                g.setColor(trackColor);
                g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
            }
        }

        private static final class StatusDot extends JComponent {
            private boolean on;
            StatusDot() { setPreferredSize(new Dimension(12, 12)); }
            void setOn(boolean value) { on = value; repaint(); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(on ? OK : OFF);
                g2.fillOval(1, 1, 10, 10);
                g2.dispose();
            }
        }

        private static final class RoundButton extends JButton {
            private Color base = ACCENT;
            private Color hoverColor = ACCENT;
            private boolean hover;

            RoundButton(String text) {
                super(text);
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setOpaque(false);
                setForeground(Color.WHITE);
                setFont(new Font("Segoe UI", Font.BOLD, 13));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }

            void setColors(Color base, Color hover) {
                this.base = base;
                this.hoverColor = hover;
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = !isEnabled() ? new Color(0x1B1F27) : (hover ? hoverColor : base);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                if (base == PANEL) {
                    g2.setColor(BORDER);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                }
                g2.setColor(isEnabled() ? Color.WHITE : MUTED);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        }
    }
}
