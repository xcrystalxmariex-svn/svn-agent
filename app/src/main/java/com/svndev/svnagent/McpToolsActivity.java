package com.svndev.svnagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * MCP / TOOLS screen. Two sections:
 *
 * <ol>
 *   <li><b>MCP servers</b> — remote HTTP MCP endpoints (like svn-dev mcp). Each
 *       has an enable toggle and, beneath it, the list of tools it exposes.</li>
 *   <li><b>Local tools</b> — on-device functions (device actions + launchable
 *       apps) exposed to the agent through the app-hosted local MCP server.</li>
 * </ol>
 *
 * Toggles are persisted immediately; they take effect the next time the engine
 * starts (the config is materialized at launch).
 */
public class McpToolsActivity extends Activity {

    private McpStore store;
    private LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new McpStore(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF050b08);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        content.removeAllViews();

        TextView title = new TextView(this);
        title.setText("MCP / TOOLS");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView note = new TextView(this);
        note.setText("changes apply when the engine next starts");
        note.setTextColor(0xFF008844);
        note.setTextSize(10);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.addView(note, margin(dp(8)));

        // ---- MCP servers ----
        content.addView(sectionHeader("MCP SERVERS  ·  remote HTTP"));
        content.addView(button("+ ADD SERVER", 0xFF00ff66, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(McpToolsActivity.this, AddServerActivity.class));
            }
        }));

        List<McpStore.Server> servers = store.loadServers();
        if (servers.isEmpty()) {
            content.addView(hint("no servers yet"));
        } else {
            for (final McpStore.Server s : servers) {
                content.addView(serverRow(s));
            }
        }

        content.addView(divider());

        // ---- Local tools ----
        content.addView(sectionHeader("LOCAL TOOLS  ·  on this device"));
        content.addView(button("+ ADD TOOL", 0xFF00ff66, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(McpToolsActivity.this, AddToolActivity.class));
            }
        }));

        List<McpStore.LocalTool> locals = store.loadLocalTools();
        if (locals.isEmpty()) {
            content.addView(hint("no local tools yet"));
        } else {
            for (final McpStore.LocalTool lt : locals) {
                content.addView(localRow(lt));
            }
        }
    }

    // ---- rows -------------------------------------------------------------

    private View serverRow(final McpStore.Server s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextColor(0xFF00ff66);
        name.setTextSize(14);
        name.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);

        TextView url = new TextView(this);
        url.setText(s.url);
        url.setTextColor(0xFF008844);
        url.setTextSize(10);
        url.setTypeface(android.graphics.Typeface.MONOSPACE);

        col.addView(name);
        col.addView(url);
        head.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(s.enabled);
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                store.setServerEnabled(s.name, isChecked));
        head.addView(sw);

        box.addView(head);

        // tool sublist
        if (s.tools.isEmpty()) {
            TextView empty = hint("    tap name to fetch tools");
            empty.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { refreshTools(s, null); }
            });
            box.addView(empty);
        } else {
            for (McpStore.Tool t : s.tools) {
                TextView tt = new TextView(this);
                tt.setText("    • " + t.name
                        + (t.description == null || t.description.isEmpty() ? "" : " — " + t.description));
                tt.setTextColor(0xFF00aa44);
                tt.setTextSize(10);
                tt.setTypeface(android.graphics.Typeface.MONOSPACE);
                tt.setPadding(0, dp(1), 0, dp(1));
                box.addView(tt);
            }
        }

        name.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshTools(s, null); }
        });
        name.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { serverMenu(s); return true; }
        });
        url.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { serverMenu(s); return true; }
        });
        return box;
    }

    private View localRow(final McpStore.LocalTool lt) {
        DeviceTools.ToolDef def = DeviceTools.byId(this, lt.id);
        String label = def != null ? def.label : lt.id;
        String cat = def != null ? def.category : "?";

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tv = new TextView(this);
        tv.setText(label + "  ·  " + cat);
        tv.setTextColor(0xFF00ff66);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(lt.enabled);
        sw.setOnCheckedChangeListener((b, checked) -> store.setLocalToolEnabled(lt.id, checked));
        row.addView(sw);

        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { localMenu(lt); return true; }
        });
        return row;
    }

    // ---- actions ----------------------------------------------------------

    private void refreshTools(final McpStore.Server s, final Runnable after) {
        final Toast t = Toast.makeText(this, "fetching tools from " + s.name + "…", Toast.LENGTH_SHORT);
        t.show();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                List<McpStore.Tool> tools = null;
                try {
                    tools = McpClient.listTools(s);
                } catch (final Exception e) {
                    err = e.getMessage();
                }
                final List<McpStore.Tool> ft = tools;
                final String fe = err;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (fe != null) {
                            Toast.makeText(McpToolsActivity.this, "fetch failed: " + fe,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            store.setServerTools(s.name, ft);
                            Toast.makeText(McpToolsActivity.this,
                                    "loaded " + ft.size() + " tools", Toast.LENGTH_SHORT).show();
                        }
                        render();
                        if (after != null) after.run();
                    }
                });
            }
        }, "mcp-fetch").start();
    }

    private void serverMenu(final McpStore.Server s) {
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(new String[] { "Refresh tools", "Edit", "Remove" },
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            refreshTools(s, null);
                        } else if (which == 1) {
                            Intent i = new Intent(McpToolsActivity.this, AddServerActivity.class);
                            i.putExtra(AddServerActivity.EXTRA_NAME, s.name);
                            startActivity(i);
                        } else {
                            confirmRemoveServer(s);
                        }
                    }
                })
                .show();
    }

    private void confirmRemoveServer(final McpStore.Server s) {
        new AlertDialog.Builder(this)
                .setTitle("Remove server")
                .setMessage("Remove \"" + s.name + "\"?")
                .setPositiveButton("REMOVE", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        store.removeServer(s.name);
                        render();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void localMenu(final McpStore.LocalTool lt) {
        DeviceTools.ToolDef def = DeviceTools.byId(this, lt.id);
        String label = def != null ? def.label : lt.id;
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(new String[] { "Remove" }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        store.removeLocalTool(lt.id);
                        render();
                    }
                })
                .show();
    }

    // ---- view helpers -----------------------------------------------------

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF00cc55);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF006633);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private TextView button(String text, int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF050b08);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(10), 0, dp(10));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF00aa44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(20);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout.LayoutParams margin(int topMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginPx;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
