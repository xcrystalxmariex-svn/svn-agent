package com.svndev.svnagent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of the "local tools" the agent can be granted: a curated set of
 * on-device functions (open a URL, share, dial, compose SMS, camera, clipboard,
 * vibrate, toast, open settings) plus every launchable app installed on the
 * device (as an individual {@code launch_app} tool per package).
 *
 * <p>Each tool carries a JSON-Schema {@code inputSchema} (for MCP
 * {@code tools/list}) and an {@link Executor} that performs the action in the
 * app process when the agent calls it.
 */
public final class DeviceTools {

    /** Performs a local tool's action; returns a short human/LLM-readable result. */
    public interface Executor {
        String execute(JSONObject args) throws Exception;
    }

    public static final class ToolDef {
        public final String id;          // stable id, e.g. "func:open_url" / "app:com.foo"
        public final String label;       // human label
        public final String category;    // "Device" or "Apps"
        public final String description; // LLM-facing description
        public final JSONObject inputSchema;
        public final Executor executor;

        ToolDef(String id, String label, String category, String description,
                JSONObject inputSchema, Executor executor) {
            this.id = id;
            this.label = label;
            this.category = category;
            this.description = description;
            this.inputSchema = inputSchema;
            this.executor = executor;
        }

        /** MCP tool name (identifier-safe). */
        public String toolName() {
            return id.replace(':', '_').replace('.', '_');
        }
    }

    private DeviceTools() {}

    // ---- schema helpers ----------------------------------------------------

    private static JSONObject obj() {
        JSONObject o = new JSONObject();
        try { o.put("type", "object"); o.put("properties", new JSONObject()); } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject prop(JSONObject schema, String name, String type, String desc, boolean required) {
        try {
            JSONObject p = new JSONObject();
            p.put("type", type);
            p.put("description", desc);
            schema.getJSONObject("properties").put(name, p);
            if (required) {
                JSONArray req = schema.optJSONArray("required");
                if (req == null) { req = new JSONArray(); schema.put("required", req); }
                req.put(name);
            }
        } catch (Exception ignored) {}
        return schema;
    }

    // ---- the curated device functions -------------------------------------

    /** Build the curated set of device functions (excludes installed apps). */
    public static List<ToolDef> deviceFunctions(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        List<ToolDef> list = new ArrayList<>();

        list.add(fn("func:open_url", "Open URL", "Open a web URL in the device browser.",
                schema("url", "string", "The URL to open", true),
                new Executor() { public String execute(JSONObject a) {
                    String url = a.optString("url", "");
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    return "opened " + url;
                }}));

        list.add(fn("func:share_text", "Share text", "Send text to another app via the Android share sheet.",
                schema("text", "string", "The text to share", true),
                new Executor() { public String execute(JSONObject a) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, a.optString("text", ""));
                    Intent c = Intent.createChooser(i, "Share");
                    c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(c);
                    return "shared text";
                }}));

        list.add(fn("func:dial", "Dial number", "Open the phone dialer with a number (does not auto-call).",
                schema("number", "string", "Phone number to dial", true),
                new Executor() { public String execute(JSONObject a) {
                    Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.optString("number", ""))));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    return "dialer opened";
                }}));

        list.add(fn("func:compose_sms", "Compose SMS", "Open the SMS composer prefilled with a number and body (user sends).",
                both(schema("number", "string", "Recipient phone number", true),
                     "body", "string", "Message body", false),
                new Executor() { public String execute(JSONObject a) {
                    String number = a.optString("number", "");
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
                    i.putExtra("sms_body", a.optString("body", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    return "sms composer opened";
                }}));

        list.add(fn("func:open_camera", "Open camera", "Launch the device camera app.",
                obj(),
                new Executor() { public String execute(JSONObject a) {
                    Intent i = new Intent("android.media.action.IMAGE_CAPTURE");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    return "camera opened";
                }}));

        list.add(fn("func:clipboard_set", "Copy to clipboard", "Put text on the device clipboard.",
                schema("text", "string", "Text to copy", true),
                new Executor() { public String execute(JSONObject a) {
                    ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null) return "clipboard unavailable";
                    cm.setPrimaryClip(ClipData.newPlainText("svn-agent", a.optString("text", "")));
                    return "copied to clipboard";
                }}));

        list.add(fn("func:clipboard_get", "Read clipboard", "Read the current clipboard text (may be empty without app focus on Android 10+).",
                obj(),
                new Executor() { public String execute(JSONObject a) {
                    ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                            || cm.getPrimaryClip().getItemCount() == 0) return "";
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(app);
                    return t == null ? "" : t.toString();
                }}));

        list.add(fn("func:toast", "Show toast", "Show a short toast message on screen.",
                schema("text", "string", "Message to show", true),
                new Executor() { public String execute(JSONObject a) {
                    android.os.Handler h = new android.os.Handler(app.getMainLooper());
                    final String msg = a.optString("text", "");
                    h.post(new Runnable() { public void run() {
                        Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                    }});
                    return "toast shown";
                }}));

        list.add(fn("func:vibrate", "Vibrate", "Vibrate the device for a number of milliseconds.",
                schema("ms", "number", "Duration in milliseconds", false),
                new Executor() { public String execute(JSONObject a) {
                    Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v == null || !v.hasVibrator()) return "no vibrator";
                    v.vibrate(Math.max(1, a.optInt("ms", 400)));
                    return "vibrated";
                }}));

        list.add(fn("func:open_settings", "Open settings", "Open the Android settings screen.",
                obj(),
                new Executor() { public String execute(JSONObject a) {
                    Intent i = new Intent(Settings.ACTION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                    return "settings opened";
                }}));

        return list;
    }

    /** Termux-backed tools (a real Linux shell on the device). */
    public static List<ToolDef> termuxTools(Context ctx) {
        final Context app = ctx.getApplicationContext();
        List<ToolDef> list = new ArrayList<>();

        list.add(new ToolDef("termux:run", "Termux: run command", "Termux",
                "Run a shell command inside the local Termux (Linux) environment and return "
                        + "its combined output. Use for anything needing a real terminal: files, "
                        + "pkg installs, ssh, git, scripts, and adb/rish when wireless debugging is "
                        + "enabled. Requires Termux installed + the RUN_COMMAND permission granted "
                        + "in the TERMUX panel.",
                schema("command", "string", "Shell command to run in Termux", true),
                new Executor() {
                    public String execute(JSONObject a) throws Exception {
                        String cmd = a.optString("command", "");
                        if (cmd.isEmpty()) {
                            return "no command provided";
                        }
                        if (!TermuxManager.isInstalled(app)) {
                            return "Termux is not installed";
                        }
                        if (!TermuxManager.hasRunCommandPermission(app)) {
                            return "Termux RUN_COMMAND permission not granted (open the TERMUX panel)";
                        }
                        TermuxConsole.command(cmd);
                        String[] r = TermuxManager.runShellSync(app, cmd, 120000);
                        TermuxConsole.result(r[0], r[1], r[2], r[3]);
                        String out = r[1] == null ? "" : r[1];
                        String err = r[2] == null ? "" : r[2];
                        String em = r[3] == null ? "" : r[3];
                        StringBuilder sb = new StringBuilder();
                        sb.append("exit=").append(r[0]).append('\n');
                        if (!out.isEmpty()) {
                            sb.append(out);
                        }
                        if (!err.isEmpty()) {
                            sb.append(err);
                        }
                        if (!em.isEmpty()) {
                            sb.append("[termux] ").append(em);
                        }
                        return sb.toString().trim();
                    }
                }));

        return list;
    }

    /** Installed launchable apps, one tool per package. */
    public static List<ToolDef> installedApps(Context ctx) {
        final Context app = ctx.getApplicationContext();
        List<ToolDef> out = new ArrayList<>();
        PackageManager pm = app.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Map<String, String> pkgLabel = new LinkedHashMap<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String pkg = ai.packageName;
            if (pkg == null || pkg.equals(app.getPackageName())) continue;
            if (pkgLabel.containsKey(pkg)) continue;
            CharSequence label = ai.loadLabel(pm);
            pkgLabel.put(pkg, label == null ? pkg : label.toString());
        }
        for (final Map.Entry<String, String> e : pkgLabel.entrySet()) {
            final String pkg = e.getKey();
            final String label = e.getValue();
            out.add(new ToolDef("app:" + pkg, label, "Apps",
                    "Launch the " + label + " app.",
                    obj(),
                    new Executor() { public String execute(JSONObject a) {
                        Intent li = app.getPackageManager().getLaunchIntentForPackage(pkg);
                        if (li == null) return "cannot launch " + pkg;
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(li);
                        return "launched " + label;
                    }}));
        }
        return out;
    }

    /** All available local tools (device functions, Termux, then apps). */
    public static List<ToolDef> available(Context ctx) {
        List<ToolDef> all = new ArrayList<>();
        all.addAll(deviceFunctions(ctx));
        all.addAll(termuxTools(ctx));
        all.addAll(installedApps(ctx));
        return all;
    }

    /** Resolve a tool id to its definition, or null. */
    public static ToolDef byId(Context ctx, String id) {
        for (ToolDef t : available(ctx)) if (t.id.equals(id)) return t;
        return null;
    }

    // ---- small builders ----------------------------------------------------

    private static ToolDef fn(String id, String label, String desc, JSONObject schema, Executor ex) {
        return new ToolDef(id, label, "Device", desc, schema, ex);
    }

    private static JSONObject schema(String n1, String t1, String d1, boolean r1) {
        return prop(obj(), n1, t1, d1, r1);
    }

    private static JSONObject both(JSONObject base, String n2, String t2, String d2, boolean r2) {
        return prop(base, n2, t2, d2, r2);
    }
}
