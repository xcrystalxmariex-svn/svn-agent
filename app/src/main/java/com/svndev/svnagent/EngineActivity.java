package com.svndev.svnagent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;

import rikka.shizuku.Shizuku;

/**
 * Engine console. On first launch it walks Shizuku onboarding (install/run
 * Shizuku + grant the API permission), downloads the prebuilt opencode engine
 * into app storage, stages it into /data/local/tmp via Shizuku, then launches
 * {@code engine serve} as the shell user and pipes its output here.
 */
public class EngineActivity extends Activity {

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String C = "svnagent";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalView terminal;
    private EditText input;
    private ProgressBar progress;
    private LinearLayout overlay;
    private TextView overlayText;
    private TextView overlayAction;

    private final EngineLauncher launcher = EngineLauncher.get();
    private EngineInstaller installer;
    private boolean requestedOnce = false;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == ShizukuHelper.REQUEST_CODE) {
                        handler.post(new Runnable() {
                            @Override public void run() { proceed(); }
                        });
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be built after attach: getApplicationContext() is null in the ctor.
        installer = new EngineInstaller(this);
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050b08);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        terminal = new TerminalView(this);
        // Long-press anywhere on the console copies the full session log.
        terminal.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                String log = terminal.dumpLog();
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("engine log", log));
                android.widget.Toast.makeText(EngineActivity.this,
                        "copied " + log.length() + " chars", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        content.addView(terminal, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(0xFF00ff66);
        input.setHintTextColor(0xFF005522);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setHint("> engine stdin (enter to send)");
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    String s = input.getText().toString();
                    input.setText("");
                    terminal.feed("> " + s + "\r\n");
                    launcher.sendLine(s);
                    return true;
                }
                return false;
            }
        });
        content.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xE6050b08);
        overlay.setPadding(dp(28), dp(28), dp(28), dp(28));

        overlayText = new TextView(this);
        overlayText.setTextColor(0xFF00ff66);
        overlayText.setTextSize(13);
        overlayText.setTypeface(android.graphics.Typeface.MONOSPACE);
        overlayText.setGravity(Gravity.CENTER);
        overlay.addView(overlayText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        plp.topMargin = dp(20);
        overlay.addView(progress, plp);

        overlayAction = new TextView(this);
        overlayAction.setTextColor(0xFF050b08);
        overlayAction.setTextSize(12);
        overlayAction.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        overlayAction.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF00ff66);
        d.setCornerRadius(dp(4));
        overlayAction.setBackground(d);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        alp.topMargin = dp(20);
        overlay.addView(overlayAction, alp);

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {}
        proceed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {}
    }

    private int enginePort() {
        String p = getSharedPreferences(C, Context.MODE_PRIVATE).getString(SettingsActivity.KEY_ENGINE_PORT, "");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return NodeMobile.DEFAULT_ENGINE_PORT;
        }
    }

    private int localMcpPort() {
        String p = getSharedPreferences(C, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_LOCAL_MCP_PORT, "4097");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return 4097;
        }
    }

    /** arm64 check — the embedded engine bundle only ships aarch64. */
    private static boolean isArm64() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /** Decide what the app needs next: Shizuku, permission, install, or launch. */
    private void proceed() {
        if (launcher.isRunning()) {
            hideOverlay();
            return;
        }
        if (!isArm64()) {
            showOverlay("Device is not 64-bit ARM — the embedded engine is aarch64 only.\n\nABIs: "
                    + android.text.TextUtils.join(", ", android.os.Build.SUPPORTED_ABIS),
                    null, null);
            return;
        }
        if (!ShizukuHelper.binderAvailable()) {
            showOverlay("Shizuku is not running.\n\nInstall Shizuku, start it via ADB / wireless debugging, "
                    + "then return here.", "OPEN SHIZUKU", new View.OnClickListener() {
                @Override public void onClick(View v) { openShizuku(); }
            });
            return;
        }
        if (!ShizukuHelper.hasPermission()) {
            if (!requestedOnce) {
                requestedOnce = true;
                ShizukuHelper.requestPermission();
            }
            showOverlay("Waiting for Shizuku permission…\n\nGrant the request for SVN Agent.",
                    "REQUEST AGAIN", new View.OnClickListener() {
                @Override public void onClick(View v) { ShizukuHelper.requestPermission(); }
            });
            return;
        }

        // Permission granted.
        if (!installer.isStaged()) {
            installAsync();
            return;
        }
        launchAsync();
    }

    private void installAsync() {
        showOverlay("Downloading local AI environment…", null, null);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    installer.install(new EngineInstaller.Progress() {
                        @Override
                        public void onProgress(final long done, final long total) {
                            handler.post(new Runnable() {
                                @Override public void run() {
                                    if (total > 0) {
                                        progress.setProgress((int) (done * 100 / total));
                                        overlayText.setText("Downloading local AI environment…\n\n"
                                                + (done / (1024 * 1024)) + " / " + (total / (1024 * 1024)) + " MB");
                                    }
                                }
                            });
                        }
                        @Override
                        public void onLog(final String line) {
                            handler.post(new Runnable() {
                                @Override public void run() { terminal.feed(line + "\r\n"); }
                            });
                        }
                    });
                    handler.post(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            hideOverlay();
                            launchAsync();
                        }
                    });
                } catch (final IOException e) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            showOverlay("Download/stage failed:\n" + e.getMessage(),
                                    "RETRY", new View.OnClickListener() {
                                        @Override public void onClick(View v) { proceed(); }
                                    });
                        }
                    });
                }
            }
        }, "engine-install").start();
    }

    private void launchAsync() {
        hideOverlay();
        final int port = enginePort();
        terminal.feed("SVN // launching engine (web, port " + port + ")…\r\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    terminalPost(installer.refreshResolver() + "\r\n");
                } catch (Throwable t) {
                    terminalPost("resolver refresh skipped: " + t.getMessage() + "\r\n");
                }
                // Diagnostic: test the engine's actual DNS + outbound path.
                // Runs concurrently so it never delays startup.
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        terminalPost("SVN // net check…\r\n");
                        terminalPost(launcher.probe("models.dev"));
                    }
                }, "netprobe").start();

                // App-hosted local MCP server (device tools) + the MCP config the
                // engine reads via OPENCODE_CONFIG (remote servers + local tools).
                String localUrl = null;
                try {
                    localUrl = LocalMcpServer.get().start(EngineActivity.this, localMcpPort());
                    terminalPost("SVN // local tools: "
                            + (localUrl == null ? "unavailable" : localUrl) + "\r\n");
                } catch (Throwable t) {
                    terminalPost("SVN // local tools failed: " + t.getMessage() + "\r\n");
                }
                try {
                    McpConfigWriter.write(EngineActivity.this, localUrl);
                } catch (Throwable t) {
                    terminalPost("SVN // mcp config write failed: " + t.getMessage() + "\r\n");
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        launchEngine(port);
                    }
                });
            }
        }, "engine-prelaunch").start();
    }

    private void launchEngine(int port) {
        try {
            launcher.launch(port, new EngineLauncher.Sink() {
                @Override
                public void onOutput(final String chunk) {
                    handler.post(new Runnable() {
                        @Override public void run() { terminal.feed(chunk); }
                    });
                }
            });
        } catch (final IOException e) {
            terminal.feed("\r\nFATAL: " + e.getMessage() + "\r\n");
            showOverlay("Engine launch failed:\n" + e.getMessage(), "RETRY",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { proceed(); }
                    });
        }
    }

    private void terminalPost(final String s) {
        handler.post(new Runnable() {
            @Override public void run() { terminal.feed(s); }
        });
    }

    private void showOverlay(String text, String actionLabel, View.OnClickListener action) {
        overlayText.setText(text);
        if (actionLabel == null) {
            overlayAction.setVisibility(View.GONE);
        } else {
            overlayAction.setVisibility(View.VISIBLE);
            overlayAction.setText(actionLabel);
            overlayAction.setOnClickListener(action);
        }
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (i == null) {
            try {
                i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU_PKG));
            } catch (ActivityNotFoundException e) {
                i = null;
            }
        }
        if (i != null) {
            try { startActivity(i); } catch (ActivityNotFoundException ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
