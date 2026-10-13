package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Import AGENT: deploys a persona bundle (PBF v1, see BUNDLE_FORMAT.md) into
 * app-private storage under filesDir/agents/<id>/ and registers it so the
 * drawer opens a fresh, self-contained agent in that home.
 *
 * Accepts a .zip or .tar.gz; format is sniffed from magic bytes. Extraction
 * is hardened against zip-slip: every relative path is validated before it
 * is written.
 */
public class PersonaImporter {

    private static final String TAG = "PersonaImporter";
    private static final String PREFS = "svnagent";
    private static final String KEY_AGENTS = "imported_agents";

    private final Context ctx;

    public PersonaImporter(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** Result of a successful import. */
    public static class AgentInfo {
        public String id;
        public String name;
        public File home;
    }

    /** All installed agent ids (comma-separated in prefs), empty when none. */
    public String[] getImportedIds() {
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AGENTS, "");
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        return raw.split(",");
    }

    public File getHomeDirFor(String id) {
        return new File(ctx.getFilesDir(), "agents/" + id);
    }

    private void addImported(String id) {
        String[] ids = getImportedIds();
        for (String s : ids) {
            if (s.equals(id)) return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids[i]);
        }
        if (sb.length() > 0) sb.append(',');
        sb.append(id);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_AGENTS, sb.toString()).apply();
        Log.i(TAG, "registered imported agent: " + id);
    }

    /** Unsafe path escape guard: returns null and drops the entry. */
    private boolean safeRel(String name) {
        if (name == null || name.isEmpty()) return false;
        File f = new File(name);
        if (f.isAbsolute()) return false;
        if (name.contains("..")) return false;
        if (name.contains("\\")) return false;
        return true;
    }

    /**
     * Deploy a bundle file. Returns the installed AgentInfo, or throws
     * IOException with a user-presentable message on failure.
     */
    public AgentInfo deploy(File bundle) throws IOException {
        File staging = new File(ctx.getCacheDir(), "persona-staging");
        deleteRecursive(staging);
        staging.mkdirs();

        BufferedInputStream in = new BufferedInputStream(new java.io.FileInputStream(bundle));
        byte[] head = new byte[4];
        int n = readSome(in, head);
        in.close();

        boolean isTarGz = n >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
        boolean isZip = n >= 2 && (head[0] & 0xff) == 0x50 && (head[1] & 0xff) == 0x4b;
        if (!isTarGz && !isZip) {
            throw new IOException("not a persona bundle (need .zip or .tar.gz)");
        }

        if (isZip) {
            unzip(bundle, staging);
        } else {
            unTarGz(bundle, staging);
        }

        File manifestFile = new File(staging, "manifest.json");
        if (!manifestFile.exists()) {
            throw new IOException("bundle has no manifest.json — not a persona bundle");
        }
        JSONObject manifest = parseManifest(manifestFile);
        String id = manifest.optString("id", "").trim();
        String name = manifest.optString("name", "").trim();
        if (id.isEmpty() || !id.matches("[a-z0-9._-]+")) {
            throw new IOException("manifest.id missing or invalid: '" + id + "'");
        }
        if (name.isEmpty()) {
            name = id;
        }

        File home = new File(ctx.getFilesDir(), "agents/" + id);
        deleteRecursive(home);
        if (!staging.renameTo(home)) {
            // rename can fail across weird storage layers; fall back to a move
            moveRecursive(staging, home);
        }

        runInstallScript(home);

        addImported(id);

        AgentInfo info = new AgentInfo();
        info.id = id;
        info.name = name;
        info.home = home;
        return info;
    }

    private JSONObject parseManifest(File f) throws IOException {
        try {
            return new JSONObject(readFile(f));
        } catch (Exception e) {
            throw new IOException("manifest.json is not valid JSON: " + e.getMessage());
        }
    }

    private String readFile(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void runInstallScript(File home) {
        File script = new File(home, "agent/install.sh");
        File sh = new File("/system/bin/sh");
        if (!script.exists() || !sh.exists()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(sh.getAbsolutePath(), script.getAbsolutePath());
            pb.environment().put("HOME", home.getAbsolutePath());
            pb.environment().put("SVN_HOME", home.getAbsolutePath());
            pb.environment().put("PERSONA_HOME", home.getAbsolutePath());
            pb.directory(home);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain to avoid a deadlock; log first ~4KB.
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int got;
            while ((got = in.read(buf)) != -1) {
                if (bos.size() < 4096) bos.write(buf, 0, Math.min(got, 4096 - bos.size()));
            }
            p.waitFor();
            Log.i(TAG, "install.sh for " + home.getName() + " rc=" + p.exitValue()
                    + " out=" + bos.toString());
        } catch (Exception e) {
            Log.w(TAG, "install.sh failed, continuing: " + e.getMessage());
        }
    }

    private void unzip(File zip, File dest) throws IOException {
        ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip));
        ZipEntry entry;
        while ((entry = zin.getNextEntry()) != null) {
            String name = entry.getName();
            if (!safeRel(name)) {
                Log.w(TAG, "skipping unsafe zip entry: " + name);
                continue;
            }
            File target = new File(dest, name);
            if (entry.isDirectory()) {
                target.mkdirs();
            } else {
                target.getParentFile().mkdirs();
                OutputStream out = new FileOutputStream(target);
                byte[] buf = new byte[65536];
                int n;
                while ((n = zin.read(buf)) != -1) out.write(buf, 0, n);
                out.close();
            }
            zin.closeEntry();
        }
        zin.close();
    }

    private void unTarGz(File gz, File dest) throws IOException {
        BufferedInputStream bin = new BufferedInputStream(
                new java.util.zip.GZIPInputStream(new java.io.FileInputStream(gz)));
        byte[] hdr = new byte[512];
        while (true) {
            int read = readFully(bin, hdr);
            if (read < 512) break;
            long size = parseOctal(hdr, 124);
            int type = hdr[156] & 0xff;
            if (size == 0 && type == 0) break;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                if (hdr[i] == 0) break;
                sb.append((char) (hdr[i] & 0xff));
            }
            String name = sb.toString();
            if (name.isEmpty() || !safeRel(name)) {
                long skip = size + ((512 - size % 512) % 512);
                skipFully(bin, skip);
                continue;
            }
            File target = new File(dest, name);
            if (type == 0 || type == '0' || type == 7 || type == 'x') {
                target.getParentFile().mkdirs();
                FileOutputStream fout = new FileOutputStream(target);
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
            } else if (type == 5) {
                target.mkdirs();
            }
            long skip = size + ((512 - size % 512) % 512);
            skipFully(bin, skip);
        }
        bin.close();
    }

    private int readSome(BufferedInputStream in, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int got = in.read(out, off, out.length - off);
            if (got < 0) break;
            off += got;
        }
        return off;
    }

    private int readFully(BufferedInputStream in, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int got = in.read(out, off, out.length - off);
            if (got < 0) break;
            off += got;
        }
        return off;
    }

    private void skipFully(BufferedInputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int c = (int) Math.min(left, buf.length);
            int got = in.read(buf, 0, c);
            if (got < 0) break;
            left -= got;
        }
    }

    private long parseOctal(byte[] hdr, int off) {
        long v = 0;
        for (int i = off; i < off + 12 && i < hdr.length; i++) {
            int c = hdr[i] & 0xff;
            if (c == 0 || c == ' ') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private void moveRecursive(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (k.isDirectory()) {
                moveRecursive(k, t);
            } else {
                copyFile(k, t);
            }
        }
        deleteRecursive(src);
    }

    private void copyFile(File from, File to) throws IOException {
        to.getParentFile().mkdirs();
        InputStream in = new java.io.FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
        in.close();
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
}