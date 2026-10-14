package com.svndev.svnagent;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Add/edit a remote MCP server: name, URL, and an optional auth header.
 * "SAVE &amp; TEST" also performs the MCP handshake and caches the tool list.
 * Pass {@link #EXTRA_NAME} to edit an existing server.
 */
public class AddServerActivity extends Activity {

    public static final String EXTRA_NAME = "server_name";

    private McpStore store;
    private LinearLayout root;
    private EditText nameField;
    private EditText urlField;
    private EditText headerField;
    private EditText valueField;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new McpStore(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(20));
        root.setBackgroundColor(0xFF050b08);

        TextView title = new TextView(this);
        title.setText("ADD MCP SERVER");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title);

        nameField = field("NAME", "e.g. svn-dev mcp");
        urlField = field("URL", "https://svn-dev.online/mcp");
        headerField = field("AUTH HEADER (optional)", "Authorization");
        valueField = field("AUTH VALUE (optional)", "Bearer <token>");

        String edit = getIntent().getStringExtra(EXTRA_NAME);
        if (edit != null) {
            McpStore.Server s = store.findServer(edit);
            if (s != null) {
                title.setText("EDIT MCP SERVER");
                nameField.setText(s.name);
                urlField.setText(s.url);
                headerField.setText(s.authHeader);
                valueField.setText(s.authValue);
            }
        }

        status = new TextView(this);
        status.setTextColor(0xFF00aa44);
        status.setTextSize(11);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setPadding(0, dp(12), 0, dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView save = button("SAVE", 0xFF00ff66);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { persist(false); }
        });
        TextView test = button("SAVE & TEST", 0xFF00cc55);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { persist(true); }
        });
        row.addView(save, weight(dp(50)));
        row.addView(test, weight(dp(50)));

        TextView back = button("BACK", 0xFF008844);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        root.addView(status);
        root.addView(row);
        root.addView(back, topMargin(dp(52), dp(10)));
        setContentView(root);
    }

    private void persist(final boolean test) {
        final String name = nameField.getText().toString().trim();
        String url = urlField.getText().toString().trim();
        String header = headerField.getText().toString().trim();
        String value = valueField.getText().toString().trim();

        if (name.isEmpty()) { toast("name required"); return; }
        if (url.isEmpty()) { toast("url required"); return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("url must start with http(s)://");
            return;
        }

        McpStore.Server s = store.findServer(name);
        boolean isNew = (s == null);
        if (isNew) s = new McpStore.Server();
        s.name = name;
        s.url = url;
        s.authHeader = header;
        s.authValue = value;
        store.upsertServer(s);

        if (!test) { toast("saved"); finish(); return; }

        status.setText("connecting to " + url + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                List<McpStore.Tool> tools = null;
                try {
                    tools = McpClient.listTools(store.findServer(name));
                } catch (final Exception e) {
                    err = e.getMessage();
                }
                final List<McpStore.Tool> ft = tools;
                final String fe = err;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (fe != null) {
                            status.setText("test failed: " + fe + "\n(server saved; fix and retry)");
                        } else {
                            store.setServerTools(name, ft);
                            status.setText("connected — " + ft.size() + " tools");
                            toast("saved, " + ft.size() + " tools");
                            finish();
                        }
                    }
                });
            }
        }, "mcp-test").start();
    }

    private EditText field(String label, String hint) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFF00cc55);
        l.setTextSize(11);
        l.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        l.setPadding(0, dp(12), 0, dp(2));
        root.addView(l);

        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF005522);
        e.setTextColor(0xFF00ff66);
        e.setSingleLine(true);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(e, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        return e;
    }

    private TextView button(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF050b08);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams weight(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private LinearLayout.LayoutParams topMargin(int h, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = margin;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
