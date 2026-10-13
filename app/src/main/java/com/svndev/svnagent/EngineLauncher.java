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

    /** Kill stale engine processes from earlier launches (shell, clean env). */
    private void killOrphans() {
        try {
            Process k = Shizuku.newProcess(new String[] { SH, "-c",
                    "pidof opencode 2>/dev/null | while read -r pp; do "
                    + "kill -9 $pp 2>/dev/null; done; echo KILLED" },
                    null, "/data/local/tmp");
            k.waitFor();
        } catch (Throwable ignored) {}
    }

    public void launch(int port, Sink sink) throws IOException {
        if (running) return;
        this.sink = sink;

        // Kill any orphaned engine from a previous app install/launch — Shizuku
        // children outlive the app and would still hold the port.
        killOrphans();

        // Direct exec of the binary — NO `sh -c` wrapper. The env array applies
        // only to opencode, so the glibc LD_PRELOAD/LD_LIBRARY_PATH never touch
        // a bionic process (a wrapping shell would preload the glibc shim into
        // /system/bin/sh itself -> "cannot link executable ... DT_NEEDED").
        final String[] cmd = {
                EngineInstaller.BIN,
                "web",
                "--hostname", "127.0.0.1",
                "--port", String.valueOf(port)
        };
        final String[] env = {
                "LD_PRELOAD=" + EngineInstaller.SHIM,
                "LD_LIBRARY_PATH=" + EngineInstaller.GLIBC_LIB,
                "SSL_CERT_FILE=" + EngineInstaller.CERT,
                "CLAUDE_RESOLV_REDIRECT=" + EngineInstaller.RESOLV,
                "HOME=" + EngineInstaller.HOME,
                "TMPDIR=" + EngineInstaller.TMP,
                "PATH=/system/bin"
        };
        // NOTE: no OPENCODE_SERVER_PASSWORD — binding is localhost-only and a
        // password makes the page's own async API calls fail auth (shows up as
        // "Cannot connect to API" in the UI).
        try {
            proc = Shizuku.newProcess(cmd, env, EngineInstaller.WORKSPACE);
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
