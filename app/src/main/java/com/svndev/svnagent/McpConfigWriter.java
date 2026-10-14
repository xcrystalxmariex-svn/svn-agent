package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;

import rikka.shizuku.Shizuku;

/**
 * Writes the app-managed opencode MCP config into the shell-owned workspace
 * ({@link EngineInstaller#MCP_CONFIG}) so the engine can read it. The engine is
 * launched with {@code OPENCODE_CONFIG=<that file>}, which opencode merges on
 * top of the global config (so the model config is untouched).
 *
 * <p>The workspace is owned by the Shizuku shell user (UID 2000) and the app
 * cannot write there directly, so the file is streamed through
 * {@code /system/bin/sh -c 'cat > <file>'} exactly like the runtime bundle.
 */
public final class McpConfigWriter {

    private static final String TAG = "McpConfigWriter";
    private static final String SH = "/system/bin/sh";

    private McpConfigWriter() {}

    /**
     * Render the current store to {@link EngineInstaller#MCP_CONFIG}.
     *
     * @param localServerUrl the app-hosted local MCP server URL (may be null)
     * @return the config path on success, or null on failure
     */
    public static String write(Context ctx, String localServerUrl) {
        try {
            McpStore store = new McpStore(ctx);
            JSONObject mcp = store.enabledServersAsMcp(localServerUrl);

            JSONObject root = new JSONObject();
            root.put("$schema", "https://opencode.ai/config.json");
            root.put("mcp", mcp);
            String json = root.toString(2);

            if (localServerUrl != null && !localServerUrl.isEmpty()) {
                Log.i(TAG, "writing MCP config: " + mcp.length() + " servers/entries, local=" + localServerUrl);
            }

            String cmd = "mkdir -p " + EngineInstaller.WORKSPACE + "/etc && cat > " + EngineInstaller.MCP_CONFIG + " && echo MCP_OK";
            Process proc;
            try {
                proc = Shizuku.newProcess(new String[] { SH, "-c", cmd }, null, "/data/local/tmp");
            } catch (Throwable t) {
                Log.w(TAG, "Shizuku newProcess failed: " + t.getMessage());
                return null;
            }
            OutputStream os = proc.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return EngineInstaller.MCP_CONFIG;
        } catch (Throwable t) {
            Log.w(TAG, "write failed: " + t.getMessage());
            return null;
        }
    }
}
