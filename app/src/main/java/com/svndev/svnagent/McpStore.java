package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent store for the MCP/Tools section: the remote MCP servers the user
 * has connected (name, url, optional auth header, enabled flag, cached tool
 * list) and the on-device local tools (id + enabled flag).
 *
 * <p>Backed by a single private JSON file ({@code filesDir/mcp.json}) using the
 * platform {@link org.json} classes — no external dependencies.
 */
public final class McpStore {

    private static final String TAG = "McpStore";
    private static final String FILE = "mcp.json";

    /** A remote MCP server connection (like svn-dev mcp). */
    public static final class Server {
        public String name = "";
        public String url = "";
        /** Header name for auth, e.g. "Authorization". Blank = no auth header. */
        public String authHeader = "";
        /** Header value, e.g. "Bearer <token>". */
        public String authValue = "";
        public boolean enabled = true;
        public final List<Tool> tools = new ArrayList<>();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("url", url);
                o.put("authHeader", authHeader);
                o.put("authValue", authValue);
                o.put("enabled", enabled);
                JSONArray arr = new JSONArray();
                for (Tool t : tools) {
                    JSONObject tt = new JSONObject();
                    tt.put("name", t.name);
                    tt.put("description", t.description);
                    arr.put(tt);
                }
                o.put("tools", arr);
            } catch (Exception ignored) {}
            return o;
        }

        public static Server fromJson(JSONObject o) {
            Server s = new Server();
            s.name = o.optString("name", "");
            s.url = o.optString("url", "");
            s.authHeader = o.optString("authHeader", "");
            s.authValue = o.optString("authValue", "");
            s.enabled = o.optBoolean("enabled", true);
            JSONArray arr = o.optJSONArray("tools");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject tt = arr.optJSONObject(i);
                    if (tt == null) continue;
                    Tool t = new Tool();
                    t.name = tt.optString("name", "");
                    t.description = tt.optString("description", "");
                    s.tools.add(t);
                }
            }
            return s;
        }
    }

    /** A tool exposed by an MCP server (cached for display). */
    public static final class Tool {
        public String name = "";
        public String description = "";
    }

    /** A local (on-device) tool the user has enabled/added. */
    public static final class LocalTool {
        public String id = "";      // e.g. "func:open_url" or "app:com.foo.bar"
        public boolean enabled = true;
    }

    private final File file;

    public McpStore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.file = new File((app != null ? app : ctx).getFilesDir(), FILE);
    }

    // ---- load / save -------------------------------------------------------

    public synchronized List<Server> loadServers() {
        return parse().first;
    }

    public synchronized List<LocalTool> loadLocalTools() {
        return parse().second;
    }

    public synchronized void saveServers(List<Server> servers) {
        Pair p = parse();
        write(servers, p.second);
    }

    public synchronized void saveLocalTools(List<LocalTool> tools) {
        Pair p = parse();
        write(p.first, tools);
    }

    private static final class Pair {
        final List<Server> first;
        final List<LocalTool> second;
        Pair(List<Server> a, List<LocalTool> b) { first = a; second = b; }
    }

    private Pair parse() {
        List<Server> servers = new ArrayList<>();
        List<LocalTool> locals = new ArrayList<>();
        if (!file.exists()) return new Pair(servers, locals);
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            String json = new String(bos.toByteArray(), "UTF-8").trim();
            if (json.isEmpty()) return new Pair(servers, locals);
            JSONObject root = new JSONObject(json);
            JSONArray sa = root.optJSONArray("servers");
            if (sa != null) {
                for (int i = 0; i < sa.length(); i++) {
                    JSONObject o = sa.optJSONObject(i);
                    if (o != null) servers.add(Server.fromJson(o));
                }
            }
            JSONArray la = root.optJSONArray("localTools");
            if (la != null) {
                for (int i = 0; i < la.length(); i++) {
                    JSONObject o = la.optJSONObject(i);
                    if (o == null) continue;
                    LocalTool t = new LocalTool();
                    t.id = o.optString("id", "");
                    t.enabled = o.optBoolean("enabled", true);
                    if (!t.id.isEmpty()) locals.add(t);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse: " + e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        return new Pair(servers, locals);
    }

    private void write(List<Server> servers, List<LocalTool> locals) {
        try {
            JSONObject root = new JSONObject();
            JSONArray sa = new JSONArray();
            for (Server s : servers) sa.put(s.toJson());
            root.put("servers", sa);
            JSONArray la = new JSONArray();
            for (LocalTool t : locals) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("enabled", t.enabled);
                la.put(o);
            }
            root.put("localTools", la);
            FileOutputStream out = new FileOutputStream(file);
            out.write(root.toString(2).getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "write: " + e.getMessage());
        }
    }

    // ---- convenience helpers ----------------------------------------------

    public synchronized Server findServer(String name) {
        for (Server s : loadServers()) if (s.name.equals(name)) return s;
        return null;
    }

    public synchronized void upsertServer(Server server) {
        List<Server> servers = loadServers();
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).name.equals(server.name)) { servers.set(i, server); saveServers(servers); return; }
        }
        servers.add(server);
        saveServers(servers);
    }

    public synchronized void removeServer(String name) {
        List<Server> servers = loadServers();
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).name.equals(name)) { servers.remove(i); break; }
        }
        saveServers(servers);
    }

    public synchronized void setServerEnabled(String name, boolean enabled) {
        List<Server> servers = loadServers();
        for (Server s : servers) if (s.name.equals(name)) s.enabled = enabled;
        saveServers(servers);
    }

    public synchronized void setServerTools(String name, List<Tool> tools) {
        List<Server> servers = loadServers();
        for (Server s : servers) {
            if (s.name.equals(name)) {
                s.tools.clear();
                s.tools.addAll(tools);
            }
        }
        saveServers(servers);
    }

    public synchronized boolean hasLocalTool(String id) {
        for (LocalTool t : loadLocalTools()) if (t.id.equals(id)) return true;
        return false;
    }

    public synchronized void addLocalTool(String id) {
        if (hasLocalTool(id)) return;
        List<LocalTool> tools = loadLocalTools();
        LocalTool t = new LocalTool();
        t.id = id;
        t.enabled = true;
        tools.add(t);
        saveLocalTools(tools);
    }

    public synchronized void removeLocalTool(String id) {
        List<LocalTool> tools = loadLocalTools();
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id.equals(id)) { tools.remove(i); break; }
        }
        saveLocalTools(tools);
    }

    public synchronized void setLocalToolEnabled(String id, boolean enabled) {
        List<LocalTool> tools = loadLocalTools();
        for (LocalTool t : tools) if (t.id.equals(id)) t.enabled = enabled;
        saveLocalTools(tools);
    }

    /** Which local tool ids are currently enabled. */
    public synchronized java.util.Set<String> enabledLocalToolIds() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (LocalTool t : loadLocalTools()) if (t.enabled) set.add(t.id);
        return set;
    }

    /** Serialize the enabled remote servers as an opencode "mcp" object. */
    public synchronized JSONObject enabledServersAsMcp(String localServerUrl) {
        JSONObject mcp = new JSONObject();
        try {
            for (Server s : loadServers()) {
                if (!s.enabled || s.url.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("type", "remote");
                o.put("url", s.url);
                o.put("enabled", true);
                if (!s.authHeader.isEmpty() && !s.authValue.isEmpty()) {
                    JSONObject h = new JSONObject();
                    h.put(s.authHeader, s.authValue);
                    o.put("headers", h);
                }
                mcp.put(sanitizeName(s.name), o);
            }
            if (localServerUrl != null && !localServerUrl.isEmpty()) {
                JSONObject o = new JSONObject();
                o.put("type", "remote");
                o.put("url", localServerUrl);
                o.put("enabled", true);
                mcp.put("svn_local", o);
            }
        } catch (Exception e) {
            Log.w(TAG, "enabledServersAsMcp: " + e.getMessage());
        }
        return mcp;
    }

    /** opencode MCP names should be simple identifiers. */
    public static String sanitizeName(String n) {
        if (n == null) return "server";
        String s = n.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isEmpty() ? "server" : s;
    }

    /** Read the raw file (for diagnostics). */
    public synchronized String raw() {
        if (!file.exists()) return "";
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }
}
