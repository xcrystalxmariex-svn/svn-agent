package com.svndev.svnagent;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import rikka.shizuku.Shizuku;

/**
 * Launches the staged opencode server out of {@code /data/local/tmp/svnagent_opencode/}
 * via Shizuku and streams stdout/stderr back to a {@link Sink}.
 *
 * <p>The glibc Bun binary is run with the bundled glibc loader (its ELF
 * interpreter was patched to {@code glibc/lib/ld-linux-aarch64.so.1}), the DNS
 * shim preloaded, and an isolated environment that never leaks host runtime
 * paths.
 */
public final class EngineLauncher {

    private static final String TAG = "EngineLauncher";
    private static final String SH = "/system/bin/sh";

    /** Basic-auth credentials the embedded WebView supplies if challenged. */
    public static final String SERVER_USER = "opencode";
    public static final String SERVER_PASSWORD = "svnagent-local";

    private static EngineLauncher INSTANCE;

    /** Process-wide singleton so the engine outlives any one Activity. */
    public static synchronized EngineLauncher get() {
        if (INSTANCE == null) INSTANCE = new EngineLauncher();
        return INSTANCE;
    }

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

        // Kill any orphaned engine from a previous app install/launch — Shizuku
        // children outlive the app and would still hold the port. We record the
        // PID when launching (sh -c 'echo $$ > pidfile; exec …') so teardown is
        // deterministic — no fragile ps parsing.
        try {
            Process killer = Shizuku.newProcess(new String[] { SH, "-c",
                    "kill -9 \"$(cat " + EngineInstaller.WORKSPACE + "/opencode.pid 2>/dev/null)\" "
                    + "2>/dev/null; echo CLEAN" },
                    null, "/data/local/tmp");
            killer.waitFor();
        } catch (Throwable ignored) {}

        // Spawn /system/bin/sh with a CLEAN env: passing LD_PRELOAD etc here makes
        // the bionic launcher itself try to preload our glibc shim into `sh` and it
        // dies ("cannot link executable ... verneed[0] in DT_NEEDED"). Instead we
        // export the glibc vars inside the script so they apply only to opencode.
        final String script =
                "cd " + EngineInstaller.WORKSPACE + "; "
                + "rm -f opencode.pid; echo $$ > opencode.pid; "
                + "export LD_PRELOAD=" + EngineInstaller.SHIM + "; "
                + "export LD_LIBRARY_PATH=" + EngineInstaller.GLIBC_LIB + "; "
                + "export SSL_CERT_FILE=" + EngineInstaller.CERT + "; "
                + "export CLAUDE_RESOLV_REDIRECT=" + EngineInstaller.RESOLV + "; "
                + "export HOME=" + EngineInstaller.HOME + "; "
                + "export TMPDIR=" + EngineInstaller.TMP + "; "
                + "export PATH=/system/bin; "
                + "exec " + EngineInstaller.BIN + " web --hostname 127.0.0.1 --port " + port;
        final String[] cmd = { SH, "-c", script };
        // NOTE: no OPENCODE_SERVER_PASSWORD — binding is localhost-only and a
        // password makes the page's own async API calls fail auth (shows up as
        // "Cannot connect to API" in the UI).
        try {
            proc = Shizuku.newProcess(cmd, null, "/data/local/tmp");
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
