package com.svndev.svnagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * MEMORY panel — pick where the agent's chat history lives and sync it.
 * Transcript lines append on every voice/engine turn; remote sync also runs
 * automatically every {@code MemoryManager.SYNC_EVERY_TURNS} turns. Online
 * backends (Google Drive / GitHub repo) mean other devices running the app
 * remember the same history.
 */
public class MemoryActivity extends Activity {

    private static final int COLOR_FG = 0xFF00ff66;
    private static final int COLOR_DIM = 0xFF00aa44;
    private static final String[] BACKENDS = {"local", "github", "drive"};

    private TextView status;
    private TextView backendLabel;
    private EditText searchBox;
    private TextView results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF050b08);
        root.setPadding(dp(14), dp(24), dp(14), dp(24));

        TextView title = new TextView(this);
        title.setText("[ MEMORY // AGENT HISTORY ]");
        title.setTextColor(COLOR_FG);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title);

        backendLabel = new TextView(this);
        backendLabel.setTextColor(COLOR_DIM);
        backendLabel.setTextSize(11);
        backendLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        backendLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(backendLabel);

        TextView pick = button("STORAGE: " + MemoryManager.backend(this).toUpperCase(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickBackend(); }
                });
        root.addView(pick, fullWidth(dp(44)));

        TextView path = button("LOCAL PATH…", new View.OnClickListener() {
            @Override public void onClick(View v) { editLocalPath(); }
        });
        root.addView(path, fullWidth(dp(44)));

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView push = button("SYNC NOW", new View.OnClickListener() {
            @Override public void onClick(View v) {
                status.setText("syncing…");
                MemoryManager.syncNow(MemoryActivity.this, new MemoryManager.Callback() {
                    @Override public void onResult(boolean ok, String message) {
                        status.setText((ok ? "✓ " : "✗ ") + message);
                    }
                });
            }
        });
        TextView pull = button("PULL", new View.OnClickListener() {
            @Override public void onClick(View v) {
                status.setText("pulling…");
                MemoryManager.pullNow(MemoryActivity.this, new MemoryManager.Callback() {
                    @Override public void onResult(boolean ok, String message) {
                        status.setText((ok ? "✓ " : "✗ ") + message);
                    }
                });
            }
        });
        syncRow.addView(push, weight());
        syncRow.addView(pull, weight());
        root.addView(syncRow, fullWidth(dp(44)));

        TextView credsHint = new TextView(this);
        credsHint.setText("Online backends take their tokens from AUTHS "
                + "(mem_github_pat / mem_github_repo, mem_drive_token / mem_drive_folder_id).");
        credsHint.setTextColor(0xFF005522);
        credsHint.setTextSize(9);
        credsHint.setPadding(0, dp(4), 0, dp(8));
        root.addView(credsHint);

        TextView searchLabel = new TextView(this);
        searchLabel.setText("keyword search history:");
        searchLabel.setTextColor(COLOR_DIM);
        searchLabel.setTextSize(10);
        root.addView(searchLabel);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextColor(COLOR_FG);
        searchBox.setHintTextColor(0xFF005522);
        searchBox.setTypeface(android.graphics.Typeface.MONOSPACE);
        searchRow.addView(searchBox, weight());
        TextView go = button("FIND", new View.OnClickListener() {
            @Override public void onClick(View v) { runSearch(); }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(80), dp(44));
        searchRow.addView(go, gp);
        root.addView(searchRow, fullWidth(dp(44)));

        status = new TextView(this);
        status.setTextColor(COLOR_DIM);
        status.setTextSize(10);
        status.setPadding(0, dp(6), 0, 0);
        root.addView(status);

        results = new TextView(this);
        results.setTextColor(0xFF00dd55);
        results.setTextSize(11);
        results.setTypeface(android.graphics.Typeface.MONOSPACE);
        results.setPadding(0, dp(6), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        UiZoom.apply(this);
        refreshBackendLabel();
    }

    private void refreshBackendLabel() {
        backendLabel.setText("backend: " + MemoryManager.backend(this)
                + "   local dir: " + MemoryManager.localDir(this).getAbsolutePath());
    }

    private void pickBackend() {
        new AlertDialog.Builder(this)
                .setTitle("Memory storage")
                .setItems(new CharSequence[]{
                        "Local device folder",
                        "GitHub repo (cross-device)",
                        "Google Drive (cross-device)"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                MemoryManager.setBackend(MemoryActivity.this, BACKENDS[which]);
                                refreshBackendLabel();
                            }
                        })
                .show();
    }

    private void editLocalPath() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(MemoryManager.localDir(this).getAbsolutePath());
        new AlertDialog.Builder(this)
                .setTitle("Local memory folder")
                .setView(in)
                .setPositiveButton("SAVE", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        MemoryManager.setLocalPath(MemoryActivity.this,
                                in.getText().toString().trim());
                        refreshBackendLabel();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void runSearch() {
        String q = searchBox.getText().toString().trim();
        List<String> hits = MemoryManager.search(this, "default", q);
        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" match(es) for \"").append(q).append("\"\n\n");
        for (String h : hits) {
            sb.append(h).append("\n");
        }
        results.setText(sb.toString());
    }

    private TextView button(String text, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF050b08);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(COLOR_FG);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        if (onClick != null) {
            b.setOnClickListener(onClick);
        }
        return b;
    }

    private LinearLayout.LayoutParams fullWidth(int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        p.setMargins(0, dp(4), 0, dp(4));
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
