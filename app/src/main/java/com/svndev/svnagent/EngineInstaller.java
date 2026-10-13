package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import rikka.shizuku.Shizuku;

/**
 * Builds the opencode workspace inside {@code /data/local/tmp/svnagent_opencode/}
 * via Shizuku (shell user, UID 2000). We use /data/local/tmp because the shell
 * user cannot read the app's private dir; everything the engine needs lives here.
 *
 * <p>Flow:
 * <ol>
 *   <li>Download the self-contained runtime bundle ({@code .tar.gz}) with a plain
 *       {@link HttpURLConnection} and gunzip it in-process.</li>
 *   <li>Stream the resulting {@code .tar} into the shell process
 *       ({@code /system/bin/sh -c 'cat > .../svnagent_opencode.tar'}) — the app
 *       cannot write /data/local/tmp directly, but Shizuku can.</li>
 *   <li>Run a small {@code /system/bin/sh} script through
 *       {@link Shizuku#newProcess(String[], String[], String)} to untar, chmod,
 *       and lay out the workspace.</li>
 * </ol>
 *
 * <p>Bundle layout (extracts into the workspace):
 * <pre>
 *   opencode                 glibc Bun binary (interpreter patched to the loader below)
 *   glibc/lib/**             glibc runtime (loader + libs + gconv)
 *   shim/claude-resolvfix.so DNS shim
 *   etc/cert.pem             CA bundle (SSL_CERT_FILE)
 *   etc/resolv.conf          nameservers for the shim
 *   home/  tmp/              runtime scratch
 * </pre>
 */
public final class EngineInstaller {

    private static final String TAG = "EngineInstaller";

    /** Proven developer temp space; readable+executable by the Shizuku shell user. */
    public static final String WORKSPACE = "/data/local/tmp/svnagent_opencode";
    public static final String TARBALL = "/data/local/tmp/svnagent_opencode.tar";
    public static final String BIN = WORKSPACE + "/opencode";
    public static final String SHIM = WORKSPACE + "/shim/claude-resolvfix.so";
    public static final String GLIBC_LIB = WORKSPACE + "/glibc/lib";
    public static final String CERT = WORKSPACE + "/etc/cert.pem";
    public static final String RESOLV = WORKSPACE + "/etc/resolv.conf";
    public static final String HOME = WORKSPACE + "/home";
    public static final String TMP = WORKSPACE + "/tmp";

    private static final String SH = "/system/bin/sh";

    /** Bump whenever the published runtime bundle changes; forces a re-download. */
    private static final String STAMP = "bundle-v2-dns";

    public interface Progress {
        void onProgress(long done, long total);
        void onLog(String line);
    }

    private final Context ctx;

    public EngineInstaller(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.ctx = app != null ? app : ctx;
    }

    /**
     * Rewrite the workspace resolver with this device's actual DNS servers
     * (from ConnectivityManager; falls back to Cloudflare/Google). Hardcoded
     * 1.1.1.1 fails on networks that block public DNS — using the phone's own
     * resolvers makes model API calls work on wifi and cellular alike.
     * Safe to call on every launch; adapts to network changes.
     */
    public void refreshResolver() {
        java.util.List<String> servers = new java.util.ArrayList<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network net = cm.getActiveNetwork();
                if (net != null) {
                    android.net.LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (java.net.InetAddress a : lp.getDnsServers()) {
                            if (a != null && a.getHostAddress() != null && !a.isLinkLocalAddress()) {
                                servers.add(a.getHostAddress());
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dns detect: " + t.getMessage());
        }
        if (servers.isEmpty()) {
            servers.add("1.1.1.1");
            servers.add("8.8.8.8");
        }
        StringBuilder sb = new StringBuilder("search .\n");
        for (String s : servers) sb.append("nameserver ").append(s).append('\n');
        String content = sb.toString().replace("'", "'\\''");
        String cmd = "mkdir -p " + WORKSPACE + "/etc"
                + " && printf '%s' '" + content + "' > " + RESOLV
                + " && echo RESOLV_OK:" + sb.toString().replace("\n", ",").replace(" ", "");
        String out = run(SH, "-c", cmd);
        Log.i(TAG, "resolver refresh: " + out.trim());
    }

    public String engineUrl() {
        String url = ctx.getSharedPreferences("svnagent", Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_ENGINE_URL, SettingsActivity.DEFAULT_ENGINE_URL);
        return url == null ? "" : url.trim();
    }

    /** True when the engine is already staged in the workspace (matching bundle). */
    public boolean isStaged() {
        String cmd = "test -x " + BIN
                + " && [ \"$(cat " + WORKSPACE + "/STAMP 2>/dev/null)\" = \"" + STAMP + "\" ]"
                + " && echo STAGED";
        return run(SH, "-c", cmd).contains("STAGED");
    }

    /** Download + stage the runtime. Blocking; call off the UI thread. */
    public void install(Progress cb) throws IOException {
        String url = engineUrl();
        if (url.isEmpty()) {
            throw new IOException("no engine_url configured (Settings)");
        }

        // 1+2) stream: HTTP -> gunzip -> shell "cat > tarball"
        log(cb, "downloading runtime bundle…");
        stageTarball(url, cb);

        // 3) build the workspace with a single shell script
        log(cb, "extracting + configuring workspace…");
        String script =
                "set -e; " +
                "W=" + WORKSPACE + "; " +
                "rm -rf \"$W\" \"" + TARBALL + "\".partial; " +
                "mkdir -p \"$W\"; " +
                "tar xf " + TARBALL + " -C \"$W\" || { echo TAR_FAIL; exit 1; }; " +
                "rm -f " + TARBALL + "; " +
                "chmod 755 \"" + BIN + "\" \"" + SHIM + "\"; " +
                "chmod -R 755 \"$W/glibc\" 2>/dev/null; " +
                "echo '" + STAMP + "' > \"$W/STAMP\"; " +
                "ls -l " + BIN + "; " +
                "echo STAGE_OK";
        String out = run(SH, "-c", script);
        log(cb, out.trim());
        if (!out.contains("STAGE_OK")) {
            throw new IOException("workspace setup failed:\n" + out.trim());
        }
        log(cb, "engine ready -> " + BIN);
    }

    private void stageTarball(String url, Progress cb) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(600000);
        conn.setRequestProperty("User-Agent", "svn-agent");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        long total = conn.getContentLength();

        // remote sink: /system/bin/sh -c 'cat > tarball'
        Process proc;
        try {
            proc = Shizuku.newProcess(
                    new String[] { SH, "-c", "cat > " + TARBALL }, null, "/data/local/tmp");
        } catch (Throwable t) {
            conn.disconnect();
            throw new IOException("Shizuku newProcess(cat) failed: " + t.getMessage());
        }

        CountingInputStream raw = new CountingInputStream(conn.getInputStream());
        GZIPInputStream in = new GZIPInputStream(raw, 65536);
        OutputStream out = proc.getOutputStream();
        byte[] buf = new byte[131072];
        long done = 0;
        long lastTick = 0;
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                long read = raw.count;
                if (cb != null && read - lastTick >= (2 << 20)) {
                    cb.onProgress(read, total > 0 ? total : read);
                    lastTick = read;
                }
            }
        } finally {
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
            conn.disconnect();
        }
        try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (cb != null) cb.onProgress(total > 0 ? total : done, total > 0 ? total : done);
        log(cb, "staged tar (" + (done / (1024 * 1024)) + " MB)");
    }

    /** Run a command via Shizuku and return its merged stdout+stderr. */
    private String run(String... cmd) {
        try {
            Process proc = Shizuku.newProcess(cmd, null, "/data/local/tmp");
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            BufferedReader e = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            while ((line = e.readLine()) != null) {
                sb.append(line).append('\n');
            }
            try { proc.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "run failed: " + t.getMessage());
            return "";
        }
    }

    private void log(Progress cb, String line) {
        Log.i(TAG, line);
        if (cb != null) cb.onLog(line);
    }

    /** Counts bytes read from the compressed HTTP stream (for progress). */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
