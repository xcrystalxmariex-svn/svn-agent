package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.Shizuku;

/**
 * Downloads the prebuilt opencode engine (native HttpURLConnection — no curl)
 * into app storage, then stages it into {@code /data/local/tmp} using Shizuku
 * and marks it executable.
 *
 * Shizuku's shell user cannot read this app's private /data/data dir, so we
 * stream the downloaded bytes through the remote process stdin
 * ({@code sh -c 'cat > /data/local/tmp/svnagent/engine'}) rather than having
 * shell read the file directly.
 */
public final class EngineInstaller {

    private static final String TAG = "EngineInstaller";
    public static final String TMP_DIR = "/data/local/tmp/svnagent";
    public static final String TMP_ENGINE = TMP_DIR + "/engine";
    public static final String TMP_HOME = TMP_DIR + "/home";

    public interface Progress {
        void onProgress(long done, long total);
        void onLog(String line);
    }

    private final Context ctx;

    public EngineInstaller(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private String prefs(String key, String def) {
        return ctx.getSharedPreferences("svnagent", Context.MODE_PRIVATE).getString(key, def);
    }

    public String engineUrl() {
        String url = prefs(SettingsActivity.KEY_ENGINE_URL, SettingsActivity.DEFAULT_ENGINE_URL);
        return url == null ? "" : url.trim();
    }

    /** True when the engine is already staged in /data/local/tmp. */
    public boolean isStaged() {
        try {
            Process p = Shizuku.newProcess(
                    new String[] { "sh", "-c", "test -x " + TMP_ENGINE + " && echo yes" },
                    null, null);
            InputStream in = p.getInputStream();
            int c = in.read();
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            in.close();
            return c == 'y';
        } catch (Throwable t) {
            return false;
        }
    }

    /** Full install: download -> stage -> chmod. Blocking; call off the UI thread. */
    public void install(Progress cb) throws IOException {
        String url = engineUrl();
        if (url.isEmpty()) {
            throw new IOException("no engine_url configured (Settings)");
        }
        File cache = new File(ctx.getFilesDir(), "engine.download");
        try {
            log(cb, "downloading " + url);
            download(new URL(url), cache, cb);
            log(cb, "download complete (" + cache.length() + " bytes), staging via Shizuku");
            stage(cache, cb);
        } finally {
            if (cache.exists()) {
                // keep on failure for inspection; delete on success is handled below
            }
        }
        cache.delete();
        log(cb, "engine installed -> " + TMP_ENGINE);
    }

    private void download(URL url, File dest, Progress cb) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
        InputStream in = conn.getInputStream();
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[65536];
        long done = 0;
        long lastTick = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            done += n;
            if (cb != null && done - lastTick >= (1 << 20)) {
                cb.onProgress(done, total);
                lastTick = done;
            }
        }
        out.close();
        in.close();
        conn.disconnect();
        if (cb != null) cb.onProgress(done, total);
    }

    private void stage(File local, Progress cb) throws IOException {
        // 1) prepare the staging dir as the shell user
        shell(new String[] { "sh", "-c", "mkdir -p " + TMP_DIR + " " + TMP_HOME + " && rm -f " + TMP_ENGINE });

        // 2) stream the bytes into the remote process stdin
        Process proc;
        try {
            proc = Shizuku.newProcess(new String[] { "sh", "-c", "cat > " + TMP_ENGINE }, null, TMP_DIR);
        } catch (Throwable t) {
            throw new IOException("Shizuku newProcess(cat) failed: " + t.getMessage());
        }
        OutputStream os = proc.getOutputStream();
        InputStream fis = new FileInputStream(local);
        byte[] buf = new byte[65536];
        int n;
        long done = 0;
        while ((n = fis.read(buf)) != -1) {
            os.write(buf, 0, n);
            done += n;
            if (cb != null && done % (4 << 20) < buf.length) cb.onLog("staged " + done + " bytes");
        }
        os.close();
        fis.close();
        try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 3) make it executable
        shell(new String[] { "sh", "-c", "chmod 755 " + TMP_ENGINE + " && ls -l " + TMP_ENGINE });
    }

    private void shell(String[] cmd) throws IOException {
        try {
            Process proc = Shizuku.newProcess(cmd, null, null);
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } catch (Throwable t) {
            throw new IOException("Shizuku shell failed: " + t.getMessage());
        }
    }

    private void log(Progress cb, String line) {
        Log.i(TAG, line);
        if (cb != null) cb.onLog(line);
    }
}
