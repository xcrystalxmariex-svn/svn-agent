package com.svndev.svnagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * AUTHS panel — manage the local credential vault ({@link AuthStore}).
 * Entries are key/value pairs (username, password, API token, endpoint…)
 * encrypted with the Android Keystore and stored like a private .env file.
 * The on-device agent reads them via {@link AuthStore#all} when a tool call
 * needs credentials.
 */
public class AuthsActivity extends Activity {

    private static final int COLOR_FG = 0xFF00ff66;
    private static final int COLOR_DIM = 0xFF00aa44;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF050b08);
        root.setPadding(dp(14), dp(24), dp(14), dp(24));

        TextView title = new TextView(this);
        title.setText("[ AUTHS // CREDENTIAL VAULT ]");
        title.setTextColor(COLOR_FG);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Values are AES-256 encrypted with an Android Keystore key. "
                + "The agent reads them automatically when a task needs auth.");
        hint.setTextColor(COLOR_DIM);
        hint.setTextSize(10);
        hint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hint.setPadding(0, dp(8), 0, dp(8));
        root.addView(hint);

        TextView add = button("+ ADD CREDENTIAL", new View.OnClickListener() {
            @Override public void onClick(View v) { showEditDialog(null); }
        });
        root.addView(add, fullWidth(dp(46)));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        UiZoom.apply(this);
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<String> keys = AuthStore.keys(this);
        if (keys.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("(no credentials stored)");
            empty.setTextColor(COLOR_DIM);
            empty.setTextSize(11);
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
            return;
        }
        for (final String key : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView name = new TextView(this);
            name.setText(key);
            name.setTextColor(COLOR_FG);
            name.setTextSize(13);
            name.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView edit = button("EDIT", new View.OnClickListener() {
                @Override public void onClick(View v) { showEditDialog(key); }
            });
            TextView del = button("DEL", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    AuthStore.remove(AuthsActivity.this, key);
                    refresh();
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    dp(64), dp(36));
            bp.leftMargin = dp(6);
            row.addView(edit, bp);
            row.addView(del, bp);
            list.addView(row);
        }
    }

    private void showEditDialog(final String existingKey) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText key = new EditText(this);
        key.setHint("key (e.g. openai_api_key)");
        key.setSingleLine(true);
        if (existingKey != null) {
            key.setText(existingKey);
            key.setEnabled(false);
        }
        final EditText value = new EditText(this);
        value.setHint("value");
        value.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existingKey != null) {
            value.setText(AuthStore.get(this, existingKey));
        }
        form.addView(key);
        form.addView(value);

        new AlertDialog.Builder(this)
                .setTitle(existingKey == null ? "Add credential" : "Edit " + existingKey)
                .setView(form)
                .setPositiveButton("SAVE", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        AuthStore.put(AuthsActivity.this,
                                key.getText().toString().trim(),
                                value.getText().toString());
                        refresh();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
