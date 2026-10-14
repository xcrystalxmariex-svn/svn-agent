package com.svndev.svnagent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared accessors for the engine settings the Voice Assistant needs (the
 * local engine port the opencode server listens on, and the app-managed local
 * MCP port). Values live in the same {@code svnagent} SharedPreferences as
 * {@link SettingsActivity}.
 */
public final class EnginePrefs {

    public static final String PREFS = "svnagent";

    private EnginePrefs() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Port the on-device opencode engine serves on (default 4096). */
    public static int enginePort(Context c) {
        String p = prefs(c).getString(SettingsActivity.KEY_ENGINE_PORT, "");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return NodeMobile.DEFAULT_ENGINE_PORT;
        }
    }

    /** Port the app-hosted local MCP server listens on (default 4097). */
    public static int localMcpPort(Context c) {
        String p = prefs(c).getString(SettingsActivity.KEY_LOCAL_MCP_PORT, "4097");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return 4097;
        }
    }
}
