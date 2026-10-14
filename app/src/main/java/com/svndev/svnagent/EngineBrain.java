package com.svndev.svnagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal client for the on-device opencode engine's HTTP API — the Voice
 * Assistant's "brain". The engine (see {@link EngineService}) serves on
 * {@code 127.0.0.1:<enginePort>} and exposes the session API:
 *
 * <pre>
 *   POST /session                       {"title": "..."}         -> {"id": "..."}
 *   POST /session/{id}/message          {"parts":[{"type":"text","text":"..."}]}
 *                                       -> {"parts":[{"type":"text"|"output_text","text":"..."}]}
 * </pre>
 *
 * (Same shape the gateway uses to drive the serve.) There is no auth on the
 * on-device engine. Because the engine carries the app's local tools (MCP
 * {@code svn_local}) and Termux, the model can act on the device on its own —
 * this client only shuttles text in and out.
 */
public final class EngineBrain {

    private static final String SESSION_PREF = "voice_session_id";

    /** Async reply callback. */
    public interface Callback {
        void onReply(String text);
        void onError(String message);
    }

    private EngineBrain() {}

    /**
     * Send {@code text} to the engine and deliver the reply. Creates a session
     * on first use and reuses it afterwards so the conversation has continuity.
     * Blocking network work runs on a background thread.
     */
    public static void ask(final Context ctx, final String text, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final int port = EnginePrefs.enginePort(app);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String sid = session(app, port);
                    String reply = message(port, sid, text);
                    cb.onReply(reply);
                } catch (Exception e) {
                    cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }, "engine-brain").start();
    }

    /** Force a fresh conversation on the next message. */
    public static void resetSession(Context ctx) {
        EnginePrefs.prefs(ctx).edit().remove(SESSION_PREF).apply();
    }

    private static String session(Context app, int port) throws Exception {
        String sid = EnginePrefs.prefs(app).getString(SESSION_PREF, "");
        if (sid != null && !sid.isEmpty()) {
            return sid;
        }
        JSONObject body = new JSONObject();
        body.put("title", "Voice Assistant");
        JSONObject resp = postJson(port, "/session", body);
        String id = resp.optString("id", "");
        if (id.isEmpty()) {
            JSONObject info = resp.optJSONObject("info");
            if (info != null) {
                id = info.optString("id", "");
            }
        }
        if (id.isEmpty()) {
            throw new Exception("engine did not return a session id");
        }
        EnginePrefs.prefs(app).edit().putString(SESSION_PREF, id).apply();
        return id;
    }

    private static String message(int port, String sid, String text) throws Exception {
        JSONObject part = new JSONObject();
        part.put("type", "text");
        part.put("text", text);
        JSONArray parts = new JSONArray();
        parts.put(part);
        JSONObject body = new JSONObject();
        body.put("parts", parts);

        JSONObject resp = postJson(port, "/session/" + sid + "/message", body);

        JSONArray out = resp.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (out != null) {
            for (int i = 0; i < out.length(); i++) {
                JSONObject p = out.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                String t = p.optString("type", "");
                if ("text".equals(t) || "output_text".equals(t)) {
                    String s = p.optString("text", "");
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(s);
                    }
                }
            }
        }
        String reply = sb.toString().trim();
        if (reply.isEmpty()) {
            throw new Exception("empty reply from engine");
        }
        return reply;
    }

    private static JSONObject postJson(int port, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String raw = readAll(is);
            if (code < 200 || code >= 300) {
                throw new Exception("engine HTTP " + code + ": " + truncate(raw));
            }
            return new JSONObject(raw);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
