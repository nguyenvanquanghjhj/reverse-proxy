package com.example.proxy.handler;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.config.ProxyConfig;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded HTTP/1.1 exchange over an explicit TCP socket. One connection per request. */
public final class UpstreamTransport implements AutoCloseable {
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    private final int connectTimeoutMs, readTimeoutMs, requestTimeoutMs, maxBodyBytes, maxHeaderBytes;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("upstream-deadlines").factory());

    public UpstreamTransport(ProxyConfig config) {
        this(config.getConnectTimeoutMs(), config.getReadTimeoutMs(), config.getRequestTimeoutMs(),
                config.getMaxBodyBytes(), config.getMaxHeaderBytes());
    }
    public UpstreamTransport(int connectTimeoutMs, int readTimeoutMs, int requestTimeoutMs,
                             int maxBodyBytes, int maxHeaderBytes) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxBodyBytes = maxBodyBytes;
        this.maxHeaderBytes = maxHeaderBytes;
        deadlines.setRemoveOnCancelPolicy(true);
    }
    public record Response(int status, Map<String, List<String>> headers, byte[] body) { }
    public Response exchange(BackendServer backend, String method, URI uri,
                             Map<String, List<String>> headers, byte[] body) throws IOException {
        return exchange(backend.getHost(), backend.getPort(), method, uri, headers, body);
    }
    public Response exchange(String host, int port, String method, URI uri,
                             Map<String, List<String>> headers, byte[] body) throws IOException {
        if (body.length > maxBodyBytes) throw new BodyLimitException("Request body exceeds limit");
        if (!method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || method.equalsIgnoreCase("CONNECT"))
            throw new ProtocolException("Unsupported request method");
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null)
            throw new ProtocolException("Only origin-form request targets are supported");
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/")) throw new ProtocolException("Invalid request target");
        String target = path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String authority = (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\nConnection: close\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        for (Map.Entry<String, List<String>> entry : endToEndHeaders(headers).entrySet()) {
            if (entry.getKey().equalsIgnoreCase("host") || entry.getKey().equalsIgnoreCase("content-length")) continue;
            if (!validHeaderName(entry.getKey())) throw new ProtocolException("Invalid header name");
            for (String value : entry.getValue()) {
                if (!validHeaderValue(value)) throw new ProtocolException("Invalid header value");
                head.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        head.append("\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (headBytes.length > maxHeaderBytes) throw new HeaderLimitException("Request headers exceed limit");
        long expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs);
        Socket socket = new Socket();
        try (socket) {
            activeSockets.add(socket);
            if (closed.get()) throw new IOException("Transport is closed");
            ScheduledFuture<?> deadline = deadlines.schedule(() -> closeQuietly(socket), requestTimeoutMs, TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(host, port), Math.min(connectTimeoutMs, requestTimeoutMs));
                socket.setSoTimeout(Math.min(readTimeoutMs, requestTimeoutMs));
                socket.setTcpNoDelay(true);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                out.write(headBytes);
                out.write(body);
                out.flush();
                return readResponse(new BufferedInputStream(socket.getInputStream()), method);
            } catch (IOException failure) {
                if (System.nanoTime() >= expires && !(failure instanceof SocketTimeoutException)) {
                    SocketTimeoutException timeout = new SocketTimeoutException("Upstream exchange deadline exceeded");
                    timeout.initCause(failure);
                    throw timeout;
                }
                throw failure;
            } finally { deadline.cancel(false); }
        } finally { activeSockets.remove(socket); }
    }
    private Response readResponse(InputStream in, String method) throws IOException {
        HeaderBudget budget = new HeaderBudget(maxHeaderBytes);
        for (int interim = 0; interim < 16; interim++) {
            String statusLine = readLine(in, maxHeaderBytes, budget);
            if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) throw new ProtocolException("Invalid upstream status line");
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, List<String>> headers = readHeaders(in, budget);
            Framing framing = framing(headers);
            if (status == 101) throw new ProtocolException("Protocol upgrades are not supported");
            if (status < 200) continue;
            boolean noBody = method.equalsIgnoreCase("HEAD") || status == 204 || status == 304;
            byte[] body;
            if (noBody) body = new byte[0];
            else if (framing.chunked) body = readChunked(in, budget);
            else if (framing.length >= 0) {
                if (framing.length > maxBodyBytes) throw new BodyLimitException("Upstream body exceeds limit");
                body = readExactly(in, (int) framing.length);
            } else body = readUntilEof(in);
            return new Response(status, endToEndHeaders(headers), body);
        }
        throw new ProtocolException("Too many informational responses");
    }
    private Map<String, List<String>> readHeaders(InputStream in, HeaderBudget budget) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in, maxHeaderBytes, budget);
            if (line.isEmpty()) return headers;
            int colon = line.indexOf(':');
            if (colon <= 0 || !validHeaderName(line.substring(0, colon))) throw new ProtocolException("Invalid upstream header");
            String value = line.substring(colon + 1).trim();
            if (!validHeaderValue(value)) throw new ProtocolException("Invalid upstream header value");
            headers.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(value);
        }
    }
    private record Framing(boolean chunked, long length) { }
    private Framing framing(Map<String, List<String>> headers) throws IOException {
        List<String> lengths = headers.get("content-length");
        List<String> encoding = headers.get("transfer-encoding");
        if (lengths != null && encoding != null) throw new ProtocolException("Ambiguous Content-Length and Transfer-Encoding");
        if (encoding != null) {
            if (encoding.size() != 1 || !encoding.getFirst().equalsIgnoreCase("chunked"))
                throw new ProtocolException("Unsupported or ambiguous transfer encoding");
            return new Framing(true, -1);
        }
        if (lengths == null) return new Framing(false, -1);
        // Exactly one unambiguous length; even identical duplicate fields are rejected.
        if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]+")) throw new ProtocolException("Invalid Content-Length");
        try { return new Framing(false, Long.parseLong(lengths.getFirst())); }
        catch (NumberFormatException e) { throw new ProtocolException("Content-Length overflow"); }
    }
    private byte[] readChunked(InputStream in, HeaderBudget budget) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in, Math.min(maxHeaderBytes, 4096), null);
            String sizeText = line.split(";", 2)[0].trim();
            if (!sizeText.matches("[0-9a-fA-F]+")) throw new ProtocolException("Invalid chunk size");
            long size;
            try { size = Long.parseLong(sizeText, 16); }
            catch (NumberFormatException e) { throw new ProtocolException("Chunk size overflow"); }
            if (size == 0) {
                Map<String, List<String>> trailers = readHeaders(in, budget);
                if (trailers.containsKey("content-length") || trailers.containsKey("transfer-encoding") || trailers.containsKey("host"))
                    throw new ProtocolException("Forbidden framing trailer");
                return body.toByteArray();
            }
            if (size > maxBodyBytes - body.size()) throw new BodyLimitException("Upstream body exceeds limit");
            copyExactly(in, body, (int) size);
            if (in.read() != '\r' || in.read() != '\n') throw new ProtocolException("Invalid chunk terminator");
        }
    }
    private byte[] readExactly(InputStream in, int length) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(length, 8192));
        copyExactly(in, body, length);
        return body.toByteArray();
    }
    private static void copyExactly(InputStream in, OutputStream out, int length) throws IOException {
        byte[] buffer = new byte[Math.min(Math.max(length, 1), 8192)];
        int remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("Truncated upstream response");
            out.write(buffer, 0, count);
            remaining -= count;
        }
    }
    private byte[] readUntilEof(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (count > maxBodyBytes - body.size()) throw new BodyLimitException("Upstream body exceeds limit");
            body.write(buffer, 0, count);
        }
        return body.toByteArray();
    }
    private static String readLine(InputStream in, int limit, HeaderBudget budget) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int next = in.read();
            if (next < 0) throw new EOFException("Truncated upstream headers");
            if (budget != null) budget.consume();
            if (next == '\r') {
                if (in.read() != '\n') throw new ProtocolException("Expected CRLF");
                if (budget != null) budget.consume();
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            if (next == '\n' || next == 0) throw new ProtocolException("Invalid line delimiter");
            if (bytes.size() >= limit) throw new HeaderLimitException("Upstream header line exceeds limit");
            bytes.write(next);
        }
    }
    public static Map<String, List<String>> endToEndHeaders(Map<String, List<String>> headers) {
        Set<String> excluded = new HashSet<>(HOP_HEADERS);
        headers.forEach((name, values) -> {
            if (name.equalsIgnoreCase("connection"))
                for (String value : values) for (String token : value.split(",")) excluded.add(token.trim().toLowerCase(Locale.ROOT));
        });
        Map<String, List<String>> clean = new LinkedHashMap<>();
        headers.forEach((name, values) -> { if (!excluded.contains(name.toLowerCase(Locale.ROOT))) clean.put(name, List.copyOf(values)); });
        return clean;
    }
    private static boolean validHeaderName(String name) { return name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"); }
    private static boolean validHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 32 && c != '\t') || c == 127 || c > 255) return false;
        }
        return true;
    }
    private static void closeQuietly(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    private static final class HeaderBudget {
        private int remaining;
        HeaderBudget(int remaining) { this.remaining = remaining; }
        void consume() throws HeaderLimitException { if (--remaining < 0) throw new HeaderLimitException("Upstream headers exceed limit"); }
    }
    public static final class BodyLimitException extends IOException { public BodyLimitException(String message) { super(message); } }
    public static final class HeaderLimitException extends IOException { public HeaderLimitException(String message) { super(message); } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        activeSockets.forEach(UpstreamTransport::closeQuietly);
        deadlines.shutdownNow();
    }
}
