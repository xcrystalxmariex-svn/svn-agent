package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One-time bootstrap: copies the bundled runtimes out of assets into
 * app-private storage, writes the opencode configuration, and initializes
 * a new agent (model/provider settings) on first run.
 *
 * All binaries live under assets/runtime/:
 *   assets/runtime/node/bin/node
 *   assets/runtime/opencode/bin/opencode.js        (opencode entry)
 *   assets/runtime/python/bin/python               (the agent's python)
 *   assets/runtime/agent/main.py                   (python agent backend)
 *
 * First run: extract everything, chmod +x what needs it, write
 * <home>/.config/opencode/opencode.json with the bundled provider config,
 * then mark initialization complete so the drawer shows.
 */
public class RuntimeBootstrap {

    private static final String TAG = "RuntimeBootstrap";
    private static final String PREFS = "svnagent";
    private static final String KEY_READY = "bootstrap_done";

    private final Context ctx;
    private File homeDir;
    private File runtimeDir;

    public RuntimeBootstrap(Context context) {
        this.ctx = context.getApplicationContext();
        this.homeDir = new File(ctx.getFilesDir(), "svn-home");
        this.runtimeDir = new File(ctx.getFilesDir(), "runtime");
    }

    public File getHomeDir() { return homeDir; }
    public File getRuntimeDir() { return runtimeDir; }

    public boolean isReady() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READY, false);
    }

    /**
     * Runs the full bootstrap. Returns stdout of the agent init (or an
     * empty string). Throws IOException on any hard failure for the caller
     * to surface.
     */
    public String setup() throws IOException {
        homeDir.mkdirs();
        runtimeDir.mkdirs();

        extractAssetDir("runtime", runtimeDir);

        ensureRuntime();

        markExecutable(new File(runtimeDir, "node/bin/node"));
        markExecutable(new File(runtimeDir, "python/bin/python"));
        markExecutable(new File(runtimeDir, "opencode/bin/opencode"));
        markExecutable(new File(runtimeDir, "opencode/bin/opencode.js"));

        writeOpencodeConfig();

        String initOut = runAgentInit();

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_READY, true).apply();
        return initOut;
    }

    /**
     * Make sure a runnable node/python/opencode exists. If the APK shipped
     * the real binaries as assets they are already present; otherwise the
     * app pulls a runtime bundle from the configured download URL (stored in
     * SharedPreferences under SettingsActivity.KEY_RUNTIME_URL) and unpacks
     * it into runtimeDir. This keeps the source zip small while still making
     * the APK self-contained once provisioned.
     */
    private void ensureRuntime() throws IOException {
        File node = new File(runtimeDir, "node/bin/node");
        File py = new File(runtimeDir, "python/bin/python");
        File opencode = new File(runtimeDir, "opencode/bin/opencode");
        if (node.exists() && (py.exists() || opencode.exists())) return;

        String url = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_RUNTIME_URL, "");
        if (url.isEmpty()) {
            throw new IOException("runtime not bundled and no RUNTIME_URL configured");
        }
        File bundle = new File(runtimeDir, "runtime.tar.gz");
        File parent = runtimeDir;
        parent.mkdirs();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(600000);
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        OutputStream out = new FileOutputStream(bundle);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
        in.close();
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200) throw new IOException("runtime download failed: HTTP " + code);

        unTarGz(bundle, runtimeDir);
        bundle.delete();
    }

    /** Minimal gzip+tar extractor (Java stdlib only; API 21+). */
    private void unTarGz(File gz, File dest) throws IOException {
        java.io.BufferedInputStream bin = new java.io.BufferedInputStream(
                new java.util.zip.GZIPInputStream(new java.io.FileInputStream(gz)));
        byte[] hdr = new byte[512];
        while (true) {
            int read = readFully(bin, hdr);
            if (read < 512) break;
            long size = parseOctal(hdr, 124);
            int type = hdr[156] & 0xff;
            if (size == 0 && type == 0) break; // end-of-archive
            String name = readName(hdr);
            if (name.isEmpty()) break;
            if (type == 0 || type == '0' || type == 7 || type == 'x') {
                File f = new File(dest, name);
                f.getParentFile().mkdirs();
                FileOutputStream fout = new FileOutputStream(f);
                long left = size;
                byte[] buf = new byte[65536];
                while (left > 0) {
                    int c = (int) Math.min(left, buf.length);
                    int got = bin.read(buf, 0, c);
                    if (got < 0) break;
                    fout.write(buf, 0, got);
                    left -= got;
                }
                fout.close();
            } else if (type == 5) { // dir
                new File(dest, name).mkdirs();
            }
            // skip file data to 512 boundary + 512 header
            long skip = size + ((512 - size % 512) % 512);
            skipFully(bin, skip);
        }
    }

    private int readFully(java.io.BufferedInputStream in, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int n = in.read(out, off, out.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private void skipFully(java.io.BufferedInputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int c = (int) Math.min(left, buf.length);
            int got = in.read(buf, 0, c);
            if (got < 0) break;
            left -= got;
        }
    }

    private String readName(byte[] hdr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (hdr[i] == 0) break;
            sb.append((char) (hdr[i] & 0xff));
        }
        return sb.toString();
    }

    private long parseOctal(byte[] hdr, int off) {
        long v = 0;
        for (int i = off; i < off + 12 && i < hdr.length; i++) {
            if (hdr[i] == 0 || hdr[i] == ' ') break;
            int c = hdr[i] & 0xff;
            if (c == ' ' || c == 0) break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    /** Recursively copy one top-level assets folder into dest. */
    private void extractAssetDir(String assetPath, File dest) throws IOException {
        readFileList(assetPath, dest);
    }

    /**
     * Wipe the extracted runtime + agent state, then reset the bootstrap
     * flag so the next setup() runs a fresh install.
     */
    public void clean() {
        deleteRecursive(homeDir);
        deleteRecursive(runtimeDir);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_READY).apply();
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }

    private void readFileList(String path, File dest) throws IOException {
        String[] children;
        try {
            children = ctx.getAssets().list(path);
        } catch (IOException e) {
            return; // no such top-level asset — nothing to extract
        }
        if (children == null) return;
        for (String child : children) {
            String full = path.isEmpty() ? child : path + "/" + child;
            File target = new File(dest, child);
            // Use a file probe: try opening; if it's the end of a path, copy.
            try {
                InputStream in = ctx.getAssets().open(full);
                copyStream(in, target);
                in.close();
            } catch (IOException e) {
                // A directory if we couldn't open it as a file
                target.mkdirs();
                File childDest = new File(dest, child);
                readFileList(full, childDest);
            }
        }
    }

    private void copyStream(InputStream in, File target) throws IOException {
        target.getParentFile().mkdirs();
        OutputStream out = new FileOutputStream(target);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
    }

    private void markExecutable(File f) {
        if (f.exists()) {
            f.setExecutable(true, false);
        }
    }

    /** Write opencode's config with a bundled default provider. */
    private void writeOpencodeConfig() throws IOException {
        File cfg = new File(homeDir, ".config/opencode");
        cfg.mkdirs();
        File json = new File(cfg, "opencode.json");
        if (!json.exists()) {
            String endpoint = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(SettingsActivity.KEY_ENDPOINT, "");
            String model = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(SettingsActivity.KEY_MODEL, "opencode/local");
            String profile = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(SettingsActivity.KEY_PROFILE, "svn");
            String provider = "bundled";
            if (!endpoint.isEmpty()) {
                provider = profile;
                String config = "{\n"
                        + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                        + "  \"model\": {\n"
                        + "    \"provider\": \"" + escape(provider) + "\",\n"
                        + "    \"model\": \"" + escape(model) + "\",\n"
                        + "    \"apiEndpoint\": \"" + escape(endpoint) + "\"\n"
                        + "  }\n"
                        + "}\n";
                OutputStream out = new FileOutputStream(json);
                out.write(config.getBytes("UTF-8"));
                out.close();
            } else {
                String config = "{\n"
                        + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                        + "  \"model\": {\n"
                        + "    \"provider\": \"bundled\",\n"
                        + "    \"model\": \"opencode/local\"\n"
                        + "  }\n"
                        + "}\n";
                OutputStream out = new FileOutputStream(json);
                out.write(config.getBytes("UTF-8"));
                out.close();
            }
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Run the python agent init so it can build its own state on first run. */
    private String runAgentInit() throws IOException {
        File py = new File(runtimeDir, "agent/main.py");
        File pyBin = new File(runtimeDir, "python/bin/python");
        File node = new File(runtimeDir, "node/bin/node");
        if (py.exists() && pyBin.exists()) {
            ProcessBuilder pb = new ProcessBuilder(pyBin.getAbsolutePath(), py.getAbsolutePath(), "--init");
            pb.environment().put("SVN_HOME", homeDir.getAbsolutePath());
            return runCapture(pb);
        }
        if (node.exists()) {
            // node --version as a smoke test of the core runtime
            ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), "--version");
            return runCapture(pb);
        }
        return "";
    }

    private String runCapture(ProcessBuilder pb) throws IOException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[2048];
        int n;
        try {
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        } catch (IOException ignored) {
        } finally {
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return new String(bos.toByteArray());
    }
}