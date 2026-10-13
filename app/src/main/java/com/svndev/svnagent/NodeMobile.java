package com.svndev.svnagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Embedded Node.js engine (Node.js for Mobile).
 *
 * <p>Boots a full Node.js runtime <em>in-process</em> on a background thread —
 * there is no separate process. The bundled JS project
 * ({@code assets/opencode_project/index.js}, the esbuild/webpack output of the
 * OpenCode core) is copied to app storage and executed by libnode.
 *
 * <p>libnode.so is resolved in order:
 * <ol>
 *   <li>{@code jniLibs/arm64-v8a/libnode.so} (packaged, loaded by the linker),</li>
 *   <li>downloaded at first run to {@code filesDir/native/libnode.so} and
 *       {@link System#load(String) System.load()}ed.</li>
 * </ol>
 *
 * <p>process stdout/stderr are redirected into a pipe in native code and read
 * here; every chunk is pushed to the registered {@link Sink} so the terminal
 * view can render it.
 */
public final class NodeMobile {

    private static final String TAG = "NodeMobile";
    private static final String PREFS = "svnagent";
    private static final String PROJECT_ASSET = "opencode_project";
    private static final String PROJECT_DIR = "nodejs-project";
    public static final int DEFAULT_ENGINE_PORT = 4096;

    /** Receives decoded stdout/stderr chunks from the embedded engine. */
    public interface Sink {
        void onOutput(String chunk);
    }

    private static boolean libLoaded = false;
    private static boolean nodeRunning = false;
    private static volatile Sink sink;
    private static File libnodeFile;
    private static Thread readerThread;

    private NodeMobile() {}

    // -- native (app/src/main/cpp/native-lib.cpp) --------------------------
    public static native int nativeStart(String libPath, String[] argv);
    public static native int nativeRedirectStdio();
    public static native void nativeRestoreStdio();

    public static void setSink(Sink s) {
        sink = s;
    }

    public static synchronized boolean isRunning() {
        return nodeRunning;
    }

    /** True when the bundled JS project is present in assets. */
    public static boolean hasProjectAssets(Context ctx) {
        try {
            String[] kids = ctx.getAssets().list(PROJECT_ASSET);
            return kids != null && kids.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Load libnode.so — first from jniLibs, else download + System.load().
     * Returns true if the native library is ready to be started.
     */
    public static synchronized boolean ensureLibnode(Context ctx) {
        if (libLoaded) return true;

        // 1) packaged in jniLibs/arm64-v8a/libnode.so
        try {
            System.loadLibrary("node");
            libLoaded = true;
            libnodeFile = null;
            Log.i(TAG, "libnode loaded from jniLibs");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "libnode not packaged in jniLibs: " + e.getMessage());
        }

        // 2) download to app storage, then System.load(absolutePath)
        File so = new File(ctx.getFilesDir(), "native/libnode.so");
        if (!so.exists() || so.length() == 0) {
            String url = prefs(ctx).getString(SettingsActivity.KEY_LIBNODE_URL, "").trim();
            if (url.isEmpty()) {
                Log.w(TAG, "libnode missing and no libnode_url configured");
                return false;
            }
            try {
                Log.i(TAG, "downloading libnode.so -> " + so);
                download(url, so);
            } catch (IOException e) {
                Log.e(TAG, "libnode download failed", e);
                return false;
            }
        }
        try {
            System.load(so.getAbsolutePath());
            libLoaded = true;
            libnodeFile = so;
            Log.i(TAG, "libnode loaded via System.load(" + so + ")");
            return true;
        } catch (UnsatisfiedLinkError e) {
            // On Android 10+ the linker refuses .so files under app data
            // (W^X). The supported path is shipping it under jniLibs.
            Log.e(TAG, "System.load(libnode) failed (Android 10+ W^X?): " + e.getMessage());
            return false;
        }
    }

    /** Boot the embedded engine. Idempotent; throws if libnode is unavailable. */
    public static synchronized void start(Context ctx) throws IOException {
        if (nodeRunning) return;
        if (!ensureLibnode(ctx)) {
            throw new IOException("embedded node engine unavailable (libnode not loaded)");
        }
        try {
            System.loadLibrary("svnnode");
        } catch (UnsatisfiedLinkError e) {
            // Native JNI shim (src/main/cpp/native-lib.cpp) not built into this
            // APK. The embedded path is optional; callers fall back to the
            // Shizuku / subprocess engine instead.
            throw new IOException("embedded node JNI shim not available: " + e.getMessage());
        }

        File proj = new File(ctx.getFilesDir(), PROJECT_DIR);
        copyAssetDir(ctx, PROJECT_ASSET, proj);

        String home = ctx.getFilesDir().getAbsolutePath();
        setEnv("HOME", home);
        setEnv("SVN_HOME", home);
        setEnv("SVN_ENGINE_PORT", String.valueOf(enginePort(ctx)));

        int fd = nativeRedirectStdio();
        if (fd >= 0) startReader(fd);

        final String lib = libnodeFile != null ? libnodeFile.getAbsolutePath() : "";
        final String entry = new File(proj, "index.js").getAbsolutePath();
        final String[] argv = new String[] { "node", entry };
        nodeRunning = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int rc = nativeStart(lib, argv);
                Log.i(TAG, "node engine exited rc=" + rc);
                nodeRunning = false;
                nativeRestoreStdio();
            }
        }, "nodejs-mobile");
        t.setDaemon(true);
        t.start();
    }

    /** POST a line of input to the engine's loopback HTTP bridge. */
    public static void sendLine(final Context ctx, final String line) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://127.0.0.1:" + enginePort(ctx) + "/input");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(10000);
                    OutputStream out = conn.getOutputStream();
                    out.write(line.getBytes(Charset.forName("UTF-8")));
                    out.close();
                    conn.getResponseCode();
                } catch (IOException e) {
                    Log.w(TAG, "sendLine failed: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "node-input").start();
    }

    private static int enginePort(Context ctx) {
        String p = prefs(ctx).getString(SettingsActivity.KEY_ENGINE_PORT, "");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return DEFAULT_ENGINE_PORT;
        }
    }

    private static void startReader(final int fd) {
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
                FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                byte[] buf = new byte[8192];
                try {
                    while (true) {
                        int n = in.read(buf);
                        if (n < 0) {
                            Thread.sleep(40);
                            continue;
                        }
                        if (n == 0) continue;
                        Sink s = sink;
                        if (s != null) s.onOutput(new String(buf, 0, n, "UTF-8"));
                    }
                } catch (IOException e) {
                    // pipe closed when the engine exits
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
        }, "node-stdio");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void setEnv(String key, String value) {
        try {
            android.system.Os.setenv(key, value, true);
        } catch (Exception e) {
            Log.w(TAG, "setenv " + key + " failed: " + e.getMessage());
        }
    }

    private static void download(String url, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(600000);
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        int code = conn.getResponseCode();
        if (code != 200) {
            in.close();
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
        in.close();
        conn.disconnect();
    }

    /** Recursively copy an assets folder into app storage. */
    static void copyAssetDir(Context ctx, String assetPath, File dest) throws IOException {
        String[] children = ctx.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            // leaf file
            InputStream in = ctx.getAssets().open(assetPath);
            dest.getParentFile().mkdirs();
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.close();
            in.close();
            return;
        }
        dest.mkdirs();
        for (String child : children) {
            String full = assetPath + "/" + child;
            copyAssetDir(ctx, full, new File(dest, child));
        }
    }
}
