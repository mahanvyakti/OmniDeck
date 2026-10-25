package com.mahanapps.omnideck.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * High-performance, pure Java HTTP/1.1 server.
 * Operates with bounded thread pools and strictly capped buffer sizes to run in < 10 MB RAM.
 */
public final class HttpServerCore {

    public interface ServerBackend {
        String getStatusJson();
        String purgeRamJson();
        String listFilesJson(String dirKey);
        String getNotesJson();
        boolean addNote(String text);
        default boolean addNoteWithSender(String text, String sender, String clientIp) {
            return addNote(text);
        }
        String listAppsJson();
        boolean launchApp(String pkg);
        boolean setAnimationScale(float scale);
        boolean performFileAction(String action, File file);
        String runSpeedTest();
        File getVaultDirectory();
        File resolveAllowedFile(String relPath);
        byte[] getAsset(String path);
        default byte[] captureScreenshot() { return null; }
        default byte[] captureScreenshot(String clientIp) { return captureScreenshot(); }
        default boolean typeText(String text) { return false; }
        default boolean clearNotes() { return false; }
        default void disconnectAll() {}
        default void onClientAction(String clientIp, String action, String detail) {}
    }

    private static final int MAX_REQ_LINE = 2048;
    private static final int MAX_HEADERS = 8192;
    private static final int MAX_JSON_BODY = 32768;
    private static final int HEADER_READ_TIMEOUT_MS = 3000;  // 3s fast timeout to prevent Slowloris thread starvation
    private static final int STREAM_READ_TIMEOUT_MS = 30000; // 30s timeout for streaming uploads/downloads

    private final int requestedPort;
    private final PairingManager pairing;
    private final DownloadTicketStore tickets;
    private final StorageGuard storageGuard;
    private final ServerBackend backend;

    private ServerSocket serverSocket;
    private ThreadPoolExecutor pool;
    private Thread acceptThread;
    private volatile boolean running = false;

    private final Semaphore uploadSemaphore = new Semaphore(1); // 1 active upload at a time
    private final Semaphore downloadSemaphore = new Semaphore(2); // 2 concurrent downloads max

    public HttpServerCore(int requestedPort,
                          PairingManager pairing,
                          DownloadTicketStore tickets,
                          StorageGuard storageGuard,
                          ServerBackend backend) {
        this.requestedPort = requestedPort;
        this.pairing = pairing;
        this.tickets = tickets;
        this.storageGuard = storageGuard;
        this.backend = backend;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        this.serverSocket = new ServerSocket(requestedPort);
        this.serverSocket.setReuseAddress(true);

        AtomicInteger threadCount = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "OmniHttp-" + threadCount.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        // Bounded worker pool: up to 6 threads, queue up to 64 requests to absorb bursts
        this.pool = new ThreadPoolExecutor(
                2, 6,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.running = true;
        this.acceptThread = new Thread(this::acceptLoop, "OmniHttp-Acceptor");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    public int getLocalPort() {
        return (serverSocket != null) ? serverSocket.getLocalPort() : -1;
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(HEADER_READ_TIMEOUT_MS);
                socket.setTcpNoDelay(true);

                try {
                    pool.execute(() -> handleConnection(socket));
                } catch (Exception rejected) {
                    sendQuickError(socket, 503, "Server Busy");
                }
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleConnection(Socket socket) {
        String clientIp = socket.getInetAddress().getHostAddress();
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            String reqLine = readLine(in, MAX_REQ_LINE);
            if (reqLine == null || reqLine.trim().isEmpty()) {
                return;
            }

            String[] parts = reqLine.split("\\s+");
            if (parts.length < 2) {
                sendResponse(out, 400, "text/plain", "Bad Request".getBytes());
                return;
            }

            String method = parts[0].toUpperCase();
            String fullUri = parts[1];

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            int totalHeaderBytes = 0;
            while (true) {
                String line = readLine(in, MAX_REQ_LINE);
                if (line == null || line.isEmpty()) break;
                totalHeaderBytes += line.length();
                if (totalHeaderBytes > MAX_HEADERS) {
                    sendResponse(out, 431, "text/plain", "Request Header Fields Too Large".getBytes());
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }

            // Headers parsed safely within fast timeout. Switch to stream timeout for request body.
            socket.setSoTimeout(STREAM_READ_TIMEOUT_MS);

            String path = fullUri;
            String query = "";
            int qIdx = fullUri.indexOf('?');
            if (qIdx >= 0) {
                path = fullUri.substring(0, qIdx);
                query = fullUri.substring(qIdx + 1);
            }
            Map<String, String> queryParams = parseQueryParams(query);

            dispatch(method, path, queryParams, headers, clientIp, in, out);
        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private void dispatch(String method,
                          String path,
                          Map<String, String> query,
                          Map<String, String> headers,
                          String clientIp,
                          InputStream in,
                          OutputStream out) throws IOException {

        // 1. Static Assets (Unauthenticated)
        if ("GET".equals(method) || "HEAD".equals(method)) {
            if ("/".equals(path) || "/index.html".equals(path)) {
                serveAsset(out, "web/index.html", "text/html; charset=utf-8", "HEAD".equals(method));
                return;
            }
            if ("/app.js".equals(path)) {
                serveAsset(out, "web/app.js", "application/javascript; charset=utf-8", "HEAD".equals(method));
                return;
            }
            if ("/app.css".equals(path)) {
                serveAsset(out, "web/app.css", "text/css; charset=utf-8", "HEAD".equals(method));
                return;
            }
            if ("/api/ping".equals(path)) {
                sendJson(out, 200, "{\"status\":\"ok\"}");
                return;
            }
        }

        // 2. Pairing & Token Issuance (Unauthenticated)
        if ("POST".equals(method) && "/api/pair".equals(path)) {
            byte[] body = readJsonBody(in, headers);
            String jsonStr = new String(body, StandardCharsets.UTF_8);
            String pin = extractJsonField(jsonStr, "pin");
            String label = extractJsonField(jsonStr, "label");
            if (label == null || label.isEmpty() || "K".equals(label)) {
                label = extractJsonField(jsonStr, "deviceName");
            }
            String osType = extractJsonField(jsonStr, "osType");
            String model = extractJsonField(jsonStr, "model");
            String browser = extractJsonField(jsonStr, "browser");
            String hostname = extractJsonField(jsonStr, "hostname");
            String clientId = extractJsonField(jsonStr, "clientId");

            // Server-side User-Agent inspection as robust fallback
            String ua = headers.get("user-agent");
            if (ua != null) {
                if (browser == null || browser.isEmpty()) {
                    if (ua.contains("Edg/")) browser = "Edge";
                    else if (ua.contains("SamsungBrowser/")) browser = "Samsung Internet";
                    else if (ua.contains("Chrome/")) browser = "Chrome";
                    else if (ua.contains("Safari/") && !ua.contains("Chrome/")) browser = "Safari";
                    else if (ua.contains("Firefox/")) browser = "Firefox";
                    else browser = "Browser";
                }
                if (osType == null || osType.isEmpty() || "device".equalsIgnoreCase(osType)) {
                    if (ua.contains("Android")) osType = "Android";
                    else if (ua.contains("iPhone")) osType = "iOS";
                    else if (ua.contains("iPad")) osType = "iPadOS";
                    else if (ua.contains("Macintosh")) osType = "macOS";
                    else if (ua.contains("Windows")) osType = "Windows";
                    else if (ua.contains("Linux")) osType = "Linux";
                }
                if ((model == null || model.isEmpty() || "K".equals(model))) {
                    if (ua.contains("SM-S938")) model = "Samsung Galaxy S25 Ultra";
                    else if (ua.contains("SM-S928")) model = "Samsung Galaxy S24 Ultra";
                    else if (ua.contains("SM-S918")) model = "Samsung Galaxy S23 Ultra";
                    else if (ua.contains("Pixel")) model = "Google Pixel";
                    else if (ua.contains("OnePlus")) model = "OnePlus";
                }
            }

            if (label == null || label.isEmpty() || "K".equals(label)) {
                label = (model != null && !model.isEmpty() && !"K".equals(model)) ? model : (osType != null ? (osType + " Device") : "Companion Device");
            }

            String token = pairing.verifyPinAndIssueToken(clientIp, pin, label, hostname, osType, model, browser, clientId);
            if (token != null) {
                backend.onClientAction(clientIp, "PAIR", label + " connected");
                sendJson(out, 200, "{\"success\":true,\"token\":\"" + token + "\"}");
            } else {
                sendJson(out, 401, "{\"success\":false,\"error\":\"Invalid PIN or rate limited\"}");
            }
            return;
        }

        // 3. Download via Ticket (Unauthenticated at endpoint, but ticket is pre-authorized)
        if (("GET".equals(method) || "HEAD".equals(method)) && (path.startsWith("/dl/") || "/api/files/download".equals(path) || "/api/vault/download".equals(path))) {
            String ticketId = path.startsWith("/dl/") ? path.substring(4) : query.get("ticket");
            File target = ticketId != null ? tickets.consumeTicket(ticketId) : null;
            if (target == null || !target.exists() || !target.isFile()) {
                sendResponse(out, 404, "text/plain", "Download ticket expired or invalid".getBytes());
                return;
            }
            serveFileDownload(out, target, headers.get("range"), "HEAD".equals(method));
            return;
        }

        // 4. Authenticated Endpoints: Validate Bearer Token
        String authHeader = headers.get("authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        }

        // SEC-10: Support disconnect via Authorization header or JSON payload without query params
        if ("/api/disconnect".equals(path) || "/api/logout".equals(path)) {
            String discToken = token;
            if (discToken == null) {
                byte[] body = readJsonBody(in, headers);
                discToken = extractJsonField(new String(body, StandardCharsets.UTF_8), "token");
            }
            if (discToken != null && pairing.isValidToken(discToken)) {
                pairing.revokeDeviceByToken(discToken);
                backend.onClientAction(clientIp, "DISCONNECT", "Companion session closed");
                sendJson(out, 200, "{\"success\":true}");
            } else {
                sendJson(out, 401, "{\"error\":\"Unauthorized - invalid token\"}");
            }
            return;
        }

        if (!pairing.isValidToken(token)) {
            sendJson(out, 401, "{\"error\":\"Unauthorized - please pair device\"}");
            return;
        }

        // --- Authenticated API Handlers ---
        if ("GET".equals(method) && "/api/status".equals(path)) {
            sendJson(out, 200, backend.getStatusJson());
            return;
        }

        if ("POST".equals(method) && ("/api/purge".equals(path) || "/api/system/purge-ram".equals(path))) {
            readJsonBody(in, headers);
            backend.onClientAction(clientIp, "PURGE_RAM", "Purged background apps");
            sendJson(out, 200, backend.purgeRamJson());
            return;
        }

        if ("GET".equals(method) && ("/api/files".equals(path) || "/api/files/list".equals(path) || "/api/vault/list".equals(path) || "/api/vault".equals(path))) {
            String dir = query.getOrDefault("dir", "vault");
            sendJson(out, 200, backend.listFilesJson(dir));
            return;
        }

        if (("/api/dl-ticket".equals(path) || "/api/files/ticket".equals(path) || "/api/vault/ticket".equals(path))) {
            String filePath = query.get("name");
            if (filePath == null || filePath.isEmpty()) {
                byte[] body = readJsonBody(in, headers);
                filePath = extractJsonField(new String(body, StandardCharsets.UTF_8), "path");
                if (filePath == null) filePath = extractJsonField(new String(body, StandardCharsets.UTF_8), "name");
            }
            File target = backend.resolveAllowedFile(filePath);
            if (target != null && target.exists() && target.isFile()) {
                String ticket = tickets.createTicket(target);
                sendJson(out, 200, "{\"ticket\":\"" + ticket + "\",\"filename\":\"" + target.getName() + "\"}");
            } else {
                sendJson(out, 404, "{\"error\":\"File not found or access denied\"}");
            }
            return;
        }

        if ("POST".equals(method) && ("/api/upload".equals(path) || "/api/files/upload".equals(path) || "/api/vault/upload".equals(path))) {
            handleUpload(query, headers, in, out, clientIp);
            return;
        }

        if ("POST".equals(method) && ("/api/file-action".equals(path) || "/api/files/action".equals(path))) {
            byte[] body = readJsonBody(in, headers);
            String jsonStr = new String(body, StandardCharsets.UTF_8);
            String action = extractJsonField(jsonStr, "action");
            String filePath = extractJsonField(jsonStr, "path");
            if (filePath == null) filePath = extractJsonField(jsonStr, "name");
            File target = backend.resolveAllowedFile(filePath);
            if (target != null && backend.performFileAction(action, target)) {
                backend.onClientAction(clientIp, "FILE_ACTION", action + " on " + target.getName());
                sendJson(out, 200, "{\"success\":true}");
            } else {
                sendJson(out, 400, "{\"success\":false,\"error\":\"Action failed or file not allowed\"}");
            }
            return;
        }

        if ("GET".equals(method) && "/api/notes".equals(path)) {
            sendJson(out, 200, backend.getNotesJson());
            return;
        }

        if ("POST".equals(method) && "/api/notes".equals(path)) {
            byte[] body = readJsonBody(in, headers);
            String json = new String(body, StandardCharsets.UTF_8);
            String text = extractJsonField(json, "text");
            if (text == null) text = extractJsonField(json, "note");
            String sender = extractJsonField(json, "sender");
            if (text != null && backend.addNoteWithSender(text, sender, clientIp)) {
                String snippet = text.replace('\n', ' ').trim();
                if (snippet.length() > 25) snippet = snippet.substring(0, 22) + "...";
                backend.onClientAction(clientIp, "CLIPBOARD", "Sent: \"" + snippet + "\"");
                sendJson(out, 200, "{\"success\":true}");
            } else {
                sendJson(out, 400, "{\"success\":false}");
            }
            return;
        }

        if ("/api/notes/clear".equals(path) || ("/api/notes".equals(path) && "DELETE".equalsIgnoreCase(method))) {
            boolean ok = backend.clearNotes();
            backend.onClientAction(clientIp, "CLEAR_CLIPBOARD", "Cleared clipboard history");
            sendJson(out, 200, "{\"success\":" + ok + ",\"message\":\"Clipboard cleared\"}");
            return;
        }

        if ("/api/devices/disconnect-all".equals(path) || ("/api/devices".equals(path) && "DELETE".equalsIgnoreCase(method))) {
            sendJson(out, 403, "{\"success\":false,\"error\":\"Disconnect All is only permitted directly from TV interface for security\"}");
            return;
        }

        if (("/api/speedtest".equals(path) || "/api/system/speedtest".equals(path))) {
            readJsonBody(in, headers);
            backend.onClientAction(clientIp, "SPEED_TEST", "Triggered network speed test");
            sendJson(out, 200, backend.runSpeedTest());
            return;
        }

        if (("/api/screenshot".equals(path) || "/api/system/screenshot".equals(path))) {
            readJsonBody(in, headers);
            byte[] pngData = backend.captureScreenshot(clientIp);
            if (pngData != null && pngData.length > 0) {
                String fname = "Screenshot_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".png";
                backend.onClientAction(clientIp, "SCREENSHOT", "Captured TV screenshot → " + fname);
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP/1.1 200 OK\r\n");
                sb.append("Content-Type: image/png\r\n");
                sb.append("Content-Length: ").append(pngData.length).append("\r\n");
                sb.append("Content-Disposition: attachment; filename=\"").append(fname).append("\"\r\n");
                sb.append("Connection: close\r\n\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(pngData);
                out.flush();
            } else {
                sendJson(out, 500, "{\"success\":false,\"error\":\"Failed to capture screenshot. Ensure TV is awake.\"}");
            }
            return;
        }

        if ("GET".equals(method) && "/api/apps".equals(path)) {
            sendJson(out, 200, backend.listAppsJson());
            return;
        }

        if ("POST".equals(method) && "/api/launch".equals(path)) {
            byte[] body = readJsonBody(in, headers);
            String pkg = extractJsonField(new String(body, StandardCharsets.UTF_8), "package");
            if (pkg != null && backend.launchApp(pkg)) {
                backend.onClientAction(clientIp, "APP_LAUNCH", "Launched " + pkg);
                sendJson(out, 200, "{\"success\":true}");
            } else {
                sendJson(out, 400, "{\"success\":false,\"error\":\"App launch failed\"}");
            }
            return;
        }

        if ("POST".equals(method) && "/api/animation".equals(path)) {
            byte[] body = readJsonBody(in, headers);
            String scaleStr = extractJsonField(new String(body, StandardCharsets.UTF_8), "scale");
            try {
                float scale = Float.parseFloat(scaleStr);
                backend.setAnimationScale(scale);
                backend.onClientAction(clientIp, "ANIMATION", "Animation scale set to " + scale + "x");
                sendJson(out, 200, "{\"success\":true}");
            } catch (Exception e) {
                sendJson(out, 400, "{\"success\":false,\"error\":\"Invalid scale parameter\"}");
            }
            return;
        }

        if ("POST".equals(method) && ("/api/type".equals(path) || "/api/keyboard/type".equals(path))) {
            byte[] body = readJsonBody(in, headers);
            String json = new String(body, StandardCharsets.UTF_8);
            String text = extractJsonField(json, "text");
            if (text == null) text = extractJsonField(json, "input");
            if (text != null && backend.typeText(text)) {
                String preview = text.length() > 20 ? text.substring(0, 17) + "..." : text;
                backend.onClientAction(clientIp, "KEYSTROKE", "Typed: \"" + preview + "\"");
                sendJson(out, 200, "{\"success\":true}");
            } else {
                sendJson(out, 400, "{\"success\":false,\"error\":\"No active text input focused or accessibility not enabled\"}");
            }
            return;
        }

        sendResponse(out, 404, "text/plain", "Endpoint Not Found".getBytes());
    }

    private void handleUpload(Map<String, String> query,
                              Map<String, String> headers,
                              InputStream in,
                              OutputStream out,
                              String clientIp) throws IOException {

        long contentLength = -1;
        String clHeader = headers.get("content-length");
        if (clHeader != null) {
            try {
                contentLength = Long.parseLong(clHeader);
            } catch (NumberFormatException ignored) {}
        }

        // SEC-07: Require explicit Content-Length to prevent unbounded chunked attacks
        if (contentLength <= 0) {
            sendResponse(out, 411, "text/plain", "Length Required (Content-Length header required)".getBytes());
            return;
        }

        // Pre-flight 500 MB floor check
        if (!storageGuard.hasSafeCapacity(contentLength)) {
            sendResponse(out, 507, "text/plain", "Insufficient TV Storage (500 MB Floor Reserve)".getBytes());
            return;
        }

        if (!uploadSemaphore.tryAcquire()) {
            sendResponse(out, 503, "text/plain", "Upload Busy (1 upload at a time)".getBytes());
            return;
        }

        try {
            String rawName = query.getOrDefault("name", "uploaded_file");
            try { rawName = URLDecoder.decode(rawName, "UTF-8"); } catch (Exception ignored) {}
            String safeName = NameSanitizer.sanitize(rawName);

            File vaultDir = backend.getVaultDirectory();
            File destFile = NameSanitizer.resolveUniqueFile(vaultDir, safeName);

            UploadWriter.UploadResult res = UploadWriter.writeStream(in, contentLength, destFile, storageGuard);

            backend.onClientAction(clientIp, "FILE_UPLOAD", "Uploaded " + res.file.getName());

            sendJson(out, 200, "{\"success\":true,\"name\":\"" + res.file.getName() +
                    "\",\"bytes\":" + res.bytesWritten + ",\"sha256\":\"" + res.sha256Hex + "\"}");
        } finally {
            uploadSemaphore.release();
        }
    }

    private void serveFileDownload(OutputStream out, File file, String rangeHeader, boolean headOnly) throws IOException {
        if (!downloadSemaphore.tryAcquire()) {
            sendResponse(out, 503, "text/plain", "Download limit reached".getBytes());
            return;
        }

        try {
            long fileLen = file.length();
            long start = 0;
            long end = fileLen - 1;
            boolean isPartial = false;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                    if (start <= end && end < fileLen) {
                        isPartial = true;
                    }
                } catch (NumberFormatException ignored) {}
            }

            long sendLen = end - start + 1;
            StringBuilder sb = new StringBuilder();
            if (isPartial) {
                sb.append("HTTP/1.1 206 Partial Content\r\n");
                sb.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLen).append("\r\n");
            } else {
                sb.append("HTTP/1.1 200 OK\r\n");
            }

            sb.append("Content-Type: application/octet-stream\r\n");
            sb.append("Content-Length: ").append(sendLen).append("\r\n");
            sb.append("Content-Disposition: attachment; filename=\"").append(file.getName()).append("\"\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Connection: close\r\n\r\n");

            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            if (headOnly) {
                out.flush();
                return;
            }

            try (FileInputStream fis = new FileInputStream(file)) {
                if (start > 0) {
                    long skipped = fis.skip(start);
                }
                byte[] buf = new byte[32 * 1024];
                long remaining = sendLen;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int r = fis.read(buf, 0, toRead);
                    if (r == -1) break;
                    out.write(buf, 0, r);
                    remaining -= r;
                }
                out.flush();
            }
        } finally {
            downloadSemaphore.release();
        }
    }

    private void serveAsset(OutputStream out, String assetPath, String mime, boolean headOnly) throws IOException {
        byte[] data = backend.getAsset(assetPath);
        if (data == null) {
            sendResponse(out, 404, "text/plain", "Asset not found".getBytes());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Content-Length: ").append(data.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) {
            out.write(data);
        }
        out.flush();
    }

    private void sendJson(OutputStream out, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        sendResponse(out, status, "application/json; charset=utf-8", bytes);
    }

    private void sendResponse(OutputStream out, int status, String mime, byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" Response\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Content-Length: ").append(data.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private void sendQuickError(Socket socket, int code, String msg) {
        try (OutputStream out = socket.getOutputStream()) {
            sendResponse(out, code, "text/plain", msg.getBytes());
        } catch (Exception ignored) {}
    }

    private static String readLine(InputStream in, int maxLen) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') {
                baos.write(b);
                if (baos.size() >= maxLen) break;
            }
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.US_ASCII.name());
    }

    private static byte[] readJsonBody(InputStream in, Map<String, String> headers) throws IOException {
        String cl = headers.get("content-length");
        if (cl == null) return new byte[0];
        int len;
        try {
            len = Math.min(MAX_JSON_BODY, Integer.parseInt(cl));
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int r = in.read(buf, read, len - read);
            if (r == -1) break;
            read += r;
        }
        return buf;
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                try { k = URLDecoder.decode(k, "UTF-8"); } catch (Exception ignored) {}
                try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) {}
                map.put(k, v);
            }
        }
        return map;
    }

    private static String extractJsonField(String json, String key) {
        if (json == null || json.trim().isEmpty() || key == null) return null;
        try {
            JSONObject obj = new JSONObject(json);
            if (!obj.has(key) || obj.isNull(key)) {
                return null;
            }
            return obj.optString(key, null);
        } catch (Exception ignored) {
            return fallbackExtractJsonField(json, key);
        }
    }

    private static String fallbackExtractJsonField(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return unescapeJsonString(m.group(1));
        }
        java.util.regex.Pattern pNum = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*([^,}\\s]+)");
        java.util.regex.Matcher mNum = pNum.matcher(json);
        if (mNum.find()) {
            return mNum.group(1).replace("\"", "");
        }
        return null;
    }

    private static String unescapeJsonString(String str) {
        if (str == null) return null;
        String unescaped = str;
        if (unescaped.contains("\\u")) {
            StringBuilder sb = new StringBuilder();
            int len = unescaped.length();
            for (int i = 0; i < len; i++) {
                char c = unescaped.charAt(i);
                if (c == '\\' && i + 5 < len && unescaped.charAt(i + 1) == 'u') {
                    try {
                        int code = Integer.parseInt(unescaped.substring(i + 2, i + 6), 16);
                        sb.append((char) code);
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {}
                }
                sb.append(c);
            }
            unescaped = sb.toString();
        }
        return unescaped.replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
    }
}
