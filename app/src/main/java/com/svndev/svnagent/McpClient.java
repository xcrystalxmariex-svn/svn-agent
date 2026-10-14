package com.svndev.svnagent;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal MCP client: connects to a <em>remote</em> MCP server (HTTP) and reads
 * its tool list via JSON-RPC ({@code initialize} → {@code notifications/
 * initialized} → {@code tools/list}). Handles both plain JSON and SSE
 * ({@code text/event-stream}) responses.
 */
public final class McpClient {

    private static final String TAG = "McpClient";
    private static final String PROTOCOL = "2024-11-05";

    private McpClient() {}

    /** Fetch the tool list from a server; throws on connection/protocol failure. */
    public static List<McpStore.Tool> listTools(McpStore.Server server) throws Exception {
        String sessionId = null;

        JSONObject init = new JSONObject();
        init.put("jsonrpc", "2.0");
        init.put("id", 1);
        init.put("method", "initialize");
        JSONObject p = new JSONObject();
        p.put("protocolVersion", PROTOCOL);
        JSONObject caps = new JSONObject();
        caps.put("tools", new JSONObject());
        p.put("capabilities", caps);
        JSONObject ci = new JSONObject();
        ci.put("name", "svn-agent");
        ci.put("version", "1.0");
        p.put("clientInfo", ci);
        init.put("params", p);

        Resp r = post(server, sessionId, init.toString());
        sessionId = r.sessionId;
        if (r.body == null) throw new Exception("no response from server");

        // fire-and-forget initialized notification
        try {
            JSONObject note = new JSONObject();
            note.put("jsonrpc", "2.0");
            note.put("method", "notifications/initialized");
            post(server, sessionId, note.toString());
        } catch (Exception ignored) {}

        JSONObject listReq = new JSONObject();
        listReq.put("jsonrpc", "2.0");
        listReq.put("id", 2);
        listReq.put("method", "tools/list");
        listReq.put("params", new JSONObject());

        Resp lr = post(server, sessionId, listReq.toString());
        JSONObject resp = lr.body;
        if (resp == null) throw new Exception("no tools/list response");

        if (resp.has("error")) {
            throw new Exception("server error: " + resp.optJSONObject("error"));
        }
        JSONObject result = resp.optJSONObject("result");
        if (result == null) throw new Exception("missing result");

        List<McpStore.Tool> out = new ArrayList<>();
        JSONArray arr = result.optJSONArray("tools");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                McpStore.Tool tool = new McpStore.Tool();
                tool.name = t.optString("name", "");
                tool.description = t.optString("description", "");
                if (!tool.name.isEmpty()) out.add(tool);
            }
        }
        return out;
    }

    private static final class Resp {
        JSONObject body;
        String sessionId;
    }

    /**
     * POST a JSON-RPC message and return the parsed response (searching SSE
     * events for the matching id when the server streams).
     */
    private static Resp post(McpStore.Server server, String sessionId, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(server.url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null && !sessionId.isEmpty()) {
            conn.setRequestProperty("mcp-session-id", sessionId);
            conn.setRequestProperty("MCP-Protocol-Version", PROTOCOL);
        }
        if (server.authHeader != null && !server.authHeader.isEmpty()
                && server.authValue != null && !server.authValue.isEmpty()) {
            conn.setRequestProperty(server.authHeader, server.authValue);
        }

        OutputStream os = conn.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.close();

        int code = conn.getResponseCode();
        String ctype = conn.getContentType();
        InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();

        Resp r = new Resp();
        String h = conn.getHeaderField("mcp-session-id");
        if (h != null) r.sessionId = h;

        String body = readAll(in);
        conn.disconnect();

        if (code == 202 || body == null || body.trim().isEmpty()) {
            return r; // accepted notification / no content
        }
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + trim(body));
        }

        String t = ctype == null ? "" : ctype.toLowerCase();
        if (t.contains("text/event-stream")) {
            r.body = parseSse(body);
        } else {
            String s = body.trim();
            if (s.startsWith("{")) r.body = new JSONObject(s);
            else r.body = parseSse(s);
        }
        return r;
    }

    /** Parse an SSE stream body, returning the last JSON-RPC object seen. */
    private static JSONObject parseSse(String body) {
        JSONObject last = null;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) continue;
            try {
                if (data.startsWith("{")) last = new JSONObject(data);
            } catch (Exception ignored) {}
        }
        return last;
    }

    private static String readAll(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "read: " + e.getMessage());
            return null;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
