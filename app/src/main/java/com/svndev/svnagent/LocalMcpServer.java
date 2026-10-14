package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

/**
 * A tiny, dependency-free MCP server hosted inside the app process on
 * {@code 127.0.0.1}. opencode connects to it as a <em>remote</em> MCP server
 * ({@code http://127.0.0.1:<port>/mcp}) and calls the enabled local tools; each
 * call is executed here in the app (launch an app, share, dial, etc.).
 *
 * <p>Implements just enough of the MCP streamable-HTTP transport: JSON-RPC over
 * POST with {@code application/json} responses ({@code initialize},
 * {@code notifications/initialized}, {@code tools/list}, {@code tools/call},
 * {@code ping}). Notifications get {@code 202 Accepted}; GET (server-to-client
 * stream) returns {@code 405}, which clients tolerate.
 */
public final class LocalMcpServer {

    private static final String TAG = "LocalMcpServer";
    private static final String PROTOCOL_DEFAULT = "2024-11-05";

    private static LocalMcpServer INSTANCE;

    public static synchronized LocalMcpServer get() {
        if (INSTANCE == null) INSTANCE = new LocalMcpServer();
        return INSTANCE;
    }

    private final Object lock = new Object();
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile int port;
    private Context ctx;

    public boolean isRunning() { return running; }
    public int getPort() { return port; }

    public synchronized String start(Context context, int wantedPort) {
        this.ctx = context.getApplicationContext();
        if (running) return url();
        try {
            server = new ServerSocket(wantedPort, 8, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            running = true;
        } catch (IOException e) {
            Log.w(TAG, "bind failed: " + e.getMessage());
            return null;
        }
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "local-mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "local MCP server on " + url());
        return url();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
            } catch (IOException e) {
                break; // socket closed on stop()
            }
            final Socket sock = s;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { handle(sock); }
            }, "local-mcp-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { sock.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String sessionHeader = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase();
                String v = line.substring(c + 1).trim();
                if (k.equals("content-length")) {
                    try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {}
                } else if (k.equals("mcp-session-id")) {
                    sessionHeader = v;
                }
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                body = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(body, off, contentLength - off);
                    if (n < 0) break;
                    off += n;
                }
            }

            if (method.equals("GET")) {
                // No server-initiated stream: 405 is acceptable to MCP clients.
                writeResp(out, 405, "Method Not Allowed", null, "text/plain", null);
                sock.close();
                return;
            }
            if (method.equals("OPTIONS")) {
                writeResp(out, 204, "No Content", null, "text/plain", null);
                sock.close();
                return;
            }
            if (!method.equals("POST")) {
                writeResp(out, 405, "Method Not Allowed", null, "text/plain", null);
                sock.close();
                return;
            }

            String reqStr = new String(body, "UTF-8").trim();
            if (reqStr.isEmpty()) {
                writeResp(out, 400, "Bad Request", "{\"error\":\"empty body\"}", "application/json", null);
                sock.close();
                return;
            }

            // Batched requests arrive as a JSON array; single requests as an object.
            if (reqStr.startsWith("[")) {
                JSONArray reqs = new JSONArray(reqStr);
                JSONArray resps = new JSONArray();
                boolean anyNotificationOnly = true;
                for (int i = 0; i < reqs.length(); i++) {
                    JSONObject r = dispatch(reqs.optJSONObject(i), sessionHeader);
                    if (r != null) { resps.put(r); anyNotificationOnly = false; }
                }
                if (anyNotificationOnly) {
                    writeResp(out, 202, "Accepted", null, "text/plain", sessionHeader);
                } else {
                    writeResp(out, 200, "OK", resps.toString(), "application/json", sessionHeader);
                }
            } else {
                JSONObject req = new JSONObject(reqStr);
                JSONObject resp = dispatch(req, sessionHeader);
                if (resp == null) {
                    writeResp(out, 202, "Accepted", null, "text/plain", sessionHeader);
                } else {
                    writeResp(out, 200, "OK", resp.toString(), "application/json", sessionHeader);
                }
            }
            sock.close();
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t.getMessage());
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    /** Returns the JSON-RPC response object, or null for notifications. */
    private JSONObject dispatch(JSONObject req, String sessionHeader) {
        if (req == null) return error(null, -32700, "Parse error");
        Object idObj = req.opt("id");
        String method = req.optString("method", "");
        boolean isNotification = (idObj == null);
        JSONObject params = req.optJSONObject("params");

        if (method.equals("initialize")) {
            String pv = params != null ? params.optString("protocolVersion", PROTOCOL_DEFAULT) : PROTOCOL_DEFAULT;
            if (pv == null || pv.isEmpty()) pv = PROTOCOL_DEFAULT;
            JSONObject result = new JSONObject();
            try {
                result.put("protocolVersion", pv);
                JSONObject caps = new JSONObject();
                JSONObject tools = new JSONObject();
                tools.put("listChanged", false);
                caps.put("tools", tools);
                result.put("capabilities", caps);
                JSONObject info = new JSONObject();
                info.put("name", "svn-agent-local");
                info.put("version", "1.0");
                result.put("serverInfo", info);
            } catch (Exception ignored) {}
            return result(idObj, result);
        }
        if (method.equals("notifications/initialized")
                || method.equals("notifications/cancelled")
                || method.startsWith("notifications/")) {
            return null; // notification: 202
        }
        if (method.equals("ping")) {
            return result(idObj, new JSONObject());
        }
        if (method.equals("tools/list")) {
            JSONObject result = new JSONObject();
            JSONArray arr = new JSONArray();
            try {
                List<DeviceTools.ToolDef> enabled = enabledTools();
                for (DeviceTools.ToolDef t : enabled) {
                    JSONObject tool = new JSONObject();
                    tool.put("name", t.toolName());
                    tool.put("description", t.description);
                    tool.put("inputSchema", t.inputSchema != null ? t.inputSchema : new JSONObject());
                    arr.put(tool);
                }
                result.put("tools", arr);
            } catch (Exception ignored) {}
            return result(idObj, result);
        }
        if (method.equals("tools/call")) {
            String name = params != null ? params.optString("name", "") : "";
            JSONObject args = params != null ? params.optJSONObject("arguments") : null;
            if (args == null) args = new JSONObject();
            DeviceTools.ToolDef def = null;
            for (DeviceTools.ToolDef t : enabledTools()) {
                if (t.toolName().equals(name)) { def = t; break; }
            }
            JSONObject result = new JSONObject();
            try {
                JSONArray content = new JSONArray();
                JSONObject text = new JSONObject();
                text.put("type", "text");
                boolean isError = false;
                if (def == null) {
                    isError = true;
                    text.put("text", "unknown or disabled tool: " + name);
                } else {
                    String out;
                    try {
                        out = def.executor.execute(args);
                    } catch (Exception e) {
                        out = "tool error: " + e.getMessage();
                        isError = true;
                    }
                    text.put("text", out == null ? "" : out);
                }
                content.put(text);
                result.put("content", content);
                result.put("isError", isError);
            } catch (Exception ignored) {}
            return result(idObj, result);
        }
        // Unknown method
        if (isNotification) return null;
        return error(idObj, -32601, "method not found: " + method);
    }

    private List<DeviceTools.ToolDef> enabledTools() {
        java.util.Set<String> enabled = new McpStore(ctx).enabledLocalToolIds();
        java.util.List<DeviceTools.ToolDef> out = new java.util.ArrayList<>();
        for (DeviceTools.ToolDef t : DeviceTools.available(ctx)) {
            if (enabled.contains(t.id)) out.add(t);
        }
        return out;
    }

    private JSONObject result(Object id, JSONObject result) {
        JSONObject o = new JSONObject();
        try { o.put("jsonrpc", "2.0"); o.put("id", id == null ? JSONObject.NULL : id); o.put("result", result); } catch (Exception ignored) {}
        return o;
    }

    private JSONObject error(Object id, int code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("jsonrpc", "2.0");
            o.put("id", id == null ? JSONObject.NULL : id);
            JSONObject e = new JSONObject();
            e.put("code", code);
            e.put("message", message);
            o.put("error", e);
        } catch (Exception ignored) {}
        return o;
    }

    // ---- minimal HTTP plumbing --------------------------------------------

    private void writeResp(OutputStream out, int code, String reason, String body,
                           String contentType, String sessionId) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(bytes.length).append("\r\n");
        h.append("Connection: close\r\n");
        if (sessionId != null && !sessionId.isEmpty()) {
            h.append("mcp-session-id: ").append(sessionId).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        if (bytes.length > 0) out.write(bytes);
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        if (b == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** A fresh session id (currently informational; server is stateless). */
    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
