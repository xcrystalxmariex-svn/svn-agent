package com.svndev.svnagent;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import rikka.shizuku.Shizuku;

/**
 * Launches the staged opencode engine via Shizuku and streams its stdout/stderr
 * back to a {@link Sink}. The engine runs as the shell user (UID 2000) from
 * {@code /data/local/tmp/svnagent}.
 *
 * Default invocation (headless server):
 *   /data/local/tmp/svnagent/engine serve --hostname 127.0.0.1 --port 4096
 */
public final class EngineLauncher {

    private static final String TAG = "EngineLauncher";

    public interface Sink {
        void onOutput(String chunk);
    }

    private Process proc;
    private OutputStream stdin;
    private volatile boolean running;
    private volatile Sink sink;

    public boolean isRunning() {
        return running;
    }

    public void launch(int port, Sink sink) throws IOException {
        if (running) return;
        this.sink = sink;

        final String[] cmd = {
                EngineInstaller.TMP_ENGINE,
                "serve",
                "--hostname", "127.0.0.1",
                "--port", String.valueOf(port)
        };
        // Shizuku's newProcess runs Runtime.exec(cmd, env, dir); the env array
        // replaces the whole environment, so provide the essentials.
        final String[] env = {
                "HOME=" + EngineInstaller.TMP_HOME,
                "TMPDIR=" + EngineInstaller.TMP_HOME,
                "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin"
        };
        try {
            proc = Shizuku.newProcess(cmd, env, EngineInstaller.TMP_DIR);
        } catch (Throwable t) {
            throw new IOException("Shizuku launch failed: " + t.getMessage());
        }
        stdin = proc.getOutputStream();
        running = true;
        pipe(proc.getInputStream(), "engine-out");
        pipe(proc.getErrorStream(), "engine-err");

        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    proc.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running = false;
                emit("\r\n[engine exited]\r\n");
            }
        }, "engine-wait");
        waiter.setDaemon(true);
        waiter.start();
    }

    public void sendLine(String line) {
        if (stdin != null && running) {
            try {
                stdin.write((line + "\n").getBytes("UTF-8"));
                stdin.flush();
            } catch (IOException e) {
                Log.w(TAG, "stdin write failed: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
    }

    private void pipe(final InputStream in, String name) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        emit(new String(buf, 0, n, "UTF-8"));
                    }
                } catch (IOException ignored) {
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private void emit(String s) {
        Sink sk = sink;
        if (sk != null) sk.onOutput(s);
    }
}
