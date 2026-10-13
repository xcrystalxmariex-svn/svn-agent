package com.svndev.svnagent;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * Settings (gear) screen. Lets SeVin point the contained agent at a model
 * endpoint, choose a provider/profile, or force a clean re-bootstrap.
 * Values are stored in SharedPreferences and applied by RuntimeBootstrap on
 * the next setup().
 */
public class SettingsActivity extends Activity {

    private static final String PREFS = "svnagent";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_PROFILE = "profile";
    public static final String KEY_RUNTIME_URL = "runtime_url";

    private SharedPreferences prefs;
    private EditText endpointField;
    private EditText modelField;
    private EditText profileField;
    private EditText runtimeUrlField;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(30), dp(20), dp(20));
        root.setBackgroundColor(0xFF050b08);

        TextView title = new TextView(this);
        title.setText("[ SVN // SETTINGS ]");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 24));

        endpointField = addField("endpoint", prefs.getString(KEY_ENDPOINT, ""),
                "agent API endpoint (blank = local)");
        modelField = addField("model", prefs.getString(KEY_MODEL, "opencode/local"),
                "model id");
        profileField = addField("profile", prefs.getString(KEY_PROFILE, "svn"),
                "provider profile");
        runtimeUrlField = addField("runtime_url", prefs.getString(KEY_RUNTIME_URL, ""),
                "https://.../runtime.tar.gz (blank = bundled only)");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView save = button("SAVE", 0xFF00ff66);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit()
                        .putString(KEY_ENDPOINT, endpointField.getText().toString().trim())
                        .putString(KEY_MODEL, modelField.getText().toString().trim())
                        .putString(KEY_PROFILE, profileField.getText().toString().trim())
                        .putString(KEY_RUNTIME_URL, runtimeUrlField.getText().toString().trim())
                        .apply();
                toast("settings saved");
                finish();
            }
        });
        TextView back = button("BACK", 0xFF008844);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        row.addView(save, lp(0, dp(52), 1f, 12));
        row.addView(back, lp(0, dp(52), 1f, 12));
        root.addView(row, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 36));

        TextView danger = button("CLEAN RE-BOOTSTRAP", 0xFFff4444);
        danger.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.setEnabled(false);
                RuntimeBootstrap boot = new RuntimeBootstrap(SettingsActivity.this);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            boot.clean();
                            boot.setup();
                        } catch (IOException e) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    toast("clean failed: " + e.getMessage());
                                    v.setEnabled(true);
                                }
                            });
                            return;
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("clean reboot OK — agent reinitialized");
                                v.setEnabled(true);
                            }
                        });
                    }
                }).start();
            }
        });
        root.addView(danger, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(52), 16));

        TextView info = new TextView(this);
        info.setText("build: SVN Agent v1.0 (aarch64 runtime)\n"
                + "home: " + new RuntimeBootstrap(this).getHomeDir().getAbsolutePath());
        info.setTextColor(0xFF008844);
        info.setTextSize(10);
        info.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(info, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 40));

        setContentView(root);
    }

    private EditText addField(String label, String value, String hint) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(0xFF00ff66);
        lbl.setTextSize(11);
        lbl.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        lbl.setPadding(2, 12, 0, 2);

        EditText ed = new EditText(this);
        ed.setText(value);
        ed.setHint(hint);
        ed.setHintTextColor(0xFF005522);
        ed.setTextColor(0xFF00ff66);
        ed.setTypeface(android.graphics.Typeface.MONOSPACE);

        wrapper.addView(lbl, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(ed, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(wrapper, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return ed;
    }

    private TextView button(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF050b08);
        b.setTextSize(11);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        return b;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int mb) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, 0);
        p.bottomMargin = dp(mb);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int mb) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.bottomMargin = dp(mb);
        return p;
    }
}