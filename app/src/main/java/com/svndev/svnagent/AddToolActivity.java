package com.svndev.svnagent;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picker for on-device local tools. Lists every accessible device function and
 * installed app; checked entries become tools the agent can call (served by the
 * app-hosted local MCP server).
 */
public class AddToolActivity extends Activity {

    private McpStore store;
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private final Set<String> initiallyChecked = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new McpStore(this);
        for (McpStore.LocalTool t : store.loadLocalTools()) {
            if (t.enabled) initiallyChecked.add(t.id);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF050b08);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("ADD LOCAL TOOLS");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("grant on-device actions + apps to the agent");
        note.setTextColor(0xFF008844);
        note.setTextSize(10);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        note.setPadding(0, dp(4), 0, dp(8));
        root.addView(note);

        List<DeviceTools.ToolDef> all = DeviceTools.available(this);
        String lastCat = null;
        for (DeviceTools.ToolDef t : all) {
            if (!t.category.equals(lastCat)) {
                String header = t.category.equals("Device") ? "DEVICE FUNCTIONS"
                        : t.category.equals("Apps") ? "APPS"
                        : t.category.toUpperCase();
                root.addView(sectionHeader(header));
                lastCat = t.category;
            }
            root.addView(toolBox(t));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView save = button("SAVE", 0xFF00ff66);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        TextView back = button("BACK", 0xFF008844);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        row.addView(save, weight(dp(50)));
        row.addView(back, weight(dp(50)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(22);
        root.addView(row, rlp);

        setContentView(scroll);
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF00cc55);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(16), 0, dp(4));
        return tv;
    }

    private CheckBox toolBox(final DeviceTools.ToolDef t) {
        CheckBox cb = new CheckBox(this);
        cb.setText(t.label);
        cb.setTextColor(0xFF00ff66);
        cb.setTextSize(12);
        cb.setTypeface(android.graphics.Typeface.MONOSPACE);
        cb.setChecked(initiallyChecked.contains(t.id));
        cb.setPadding(0, dp(4), 0, dp(4));
        boxes.put(t.id, cb);
        return cb;
    }

    private void save() {
        List<DeviceTools.ToolDef> all = DeviceTools.available(this);
        List<String> selected = new ArrayList<>();
        for (DeviceTools.ToolDef t : all) {
            CheckBox cb = boxes.get(t.id);
            if (cb != null && cb.isChecked()) selected.add(t.id);
        }
        Set<String> sel = new HashSet<>(selected);
        Set<String> existing = new HashSet<>();
        for (McpStore.LocalTool lt : store.loadLocalTools()) existing.add(lt.id);

        for (String id : selected) {
            if (!existing.contains(id)) store.addLocalTool(id);
            store.setLocalToolEnabled(id, true);
        }
        for (String id : existing) {
            if (!sel.contains(id)) store.removeLocalTool(id);
        }
        Toast.makeText(this, "saved " + selected.size() + " tools", Toast.LENGTH_SHORT).show();
        finish();
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

    private LinearLayout.LayoutParams weight(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
