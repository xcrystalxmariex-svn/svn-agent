package com.svndev.svnagent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * In-app terminal. Spawns the bundled opencode CLI (via node) with the
 * in-app home, feeds its stdout into the ANSI TerminalView, and pipes
 * keystrokes from the on-screen input back to its stdin — the same TUI
 * you get from `opencode` in a shell.
 */
public class TerminalActivity extends Activity implements Runnable {

    /** Optional: absolute path to an imported agent's home (filesDir/agents/<id>). */
    public static final String EXTRA_HOME = "agent_home";

    private TerminalView terminal;
    private EditText input;
    private Handler handler;
    private Process process;
    private OutputStream procIn;
    private volatile boolean alive = false;
    private Thread readerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050b08);

        terminal = new TerminalView(this);
        root.addView(terminal, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout bottom = new FrameLayout(this);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(0xFF00ff66);
        input.setHintTextColor(0xFF005522);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setHint("> input (enter to send)");
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendInput();
                    return true;
                }
                return false;
            }
        });
        bottom.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56)));
        ((FrameLayout.LayoutParams) input.getLayoutParams()).gravity =
                android.view.Gravity.BOTTOM | android.view.Gravity.FILL_HORIZONTAL;

        root.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        startAgent();
    }

    private void startAgent() {
        RuntimeBootstrap boot = new RuntimeBootstrap(this);
        String extraHome = getIntent() != null ? getIntent().getStringExtra(EXTRA_HOME) : null;
        File home = extraHome != null && !extraHome.isEmpty()
                ? new File(extraHome)
                : boot.getHomeDir();
        File runtime = boot.getRuntimeDir();
        File node = new File(runtime, "node/bin/node");
        File py = new File(runtime, "python/bin/python");
        File agentPy = new File(runtime, "agent/main.py");
        File personaPy = new File(home, "agent/main.py");

        // Re-run bootstrap if first launch during an install wasn't complete.
        if (!node.exists() && !py.exists()) {
            try {
                boot.setup();
            } catch (IOException e) {
                terminal.feed("\nFATAL: runtime missing: " + e.getMessage() + "\n");
                return;
            }
        }

        try {
            ProcessBuilder pb;
            // Imported persona wins: run its own python entry point in its own home.
            if (personaPy.exists() && py.exists()) {
                pb = new ProcessBuilder(py.getAbsolutePath(), personaPy.getAbsolutePath());
            } else if (node.exists()) {
                pb = new ProcessBuilder(node.getAbsolutePath(),
                        new File(runtime, "opencode/bin/opencode").getAbsolutePath());
            } else {
                pb = new ProcessBuilder(py.getAbsolutePath(), agentPy.getAbsolutePath());
            }
            pb.environment().put("HOME", home.getAbsolutePath());
            pb.environment().put("NO_COLOR", "0");
            pb.environment().put("SVN_HOME", home.getAbsolutePath());
            pb.environment().put("PERSONA_HOME", home.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.directory(home);
            process = pb.start();
            procIn = process.getOutputStream();
            alive = true;
            readerThread = new Thread(this, "terminal-reader");
            readerThread.start();
            terminal.feed("SVN // agent session attached (home=" + home.getPath() + ")\r\n");
        } catch (IOException e) {
            terminal.feed("\nFATAL: " + e.getMessage() + "\n");
        }
    }

    @Override
    public void run() {
        byte[] buf = new byte[4096];
        InputStream in = process.getInputStream();
        try {
            int n;
            while (alive && (n = in.read(buf)) != -1) {
                final byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        terminal.feed(chunk, chunk.length);
                    }
                });
            }
        } catch (IOException ignored) {
        } finally {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    terminal.feed("\r\n[process exited]\r\n");
                }
            });
        }
    }

    private void sendInput() {
        String s = input.getText().toString();
        input.setText("");
        if (procIn != null && alive) {
            try {
                // Send only when the process is interactive too.
                terminal.feed("> " + s + "\r\n");
                procIn.write((s + "\r").getBytes("UTF-8"));
                procIn.flush();
            } catch (IOException ignored) {
            }
        } else {
            terminal.feed("\r\n[no process] " + s + "\r\n");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        try { if (procIn != null) procIn.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }
}