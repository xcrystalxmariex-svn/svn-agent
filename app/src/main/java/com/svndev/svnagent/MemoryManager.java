package com.svndev.svnagent;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Persistent agent memory: chat transcripts and free-form agent notes,
 * surviving across sessions/models. Default storage is a local folder
 * (filesDir/memory/, user-customizable). Optionally the same transcript file
 * is mirrored to a Google Drive file or a path in a private GitHub repo so
 * other devices running SVN Agent can share recall.
 *
 * Sync policy: every append writes locally immediately; the remote backend is
 * pushed every {@link #SYNC_EVERY_TURNS} turns and on explicit {@link
 * #syncNow}. Remote credentials come from {@link AuthStore}.
 *
 * Backend config keys (SharedPreferences "svnagent"):
 *   mem_backend      local|github|drive   (default local)
 *   mem_local_path   absolute dir for local backend (default filesDir/memory)
 * AuthStore keys:
 *   mem_github_pat        GitHub personal access token
 *   mem_github_repo       owner/repo
 *   mem_github_path       file path in repo (default svn-agent-memory.md)
 *   mem_drive_token       Google OAuth2 access token with drive.file scope
 *   mem_drive_folder_id   Drive folder id (empty = app root search)
 */
public final class MemoryManager {

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private static final String TAG = "MemoryManager";
    private static final String KEY_BACKEND = "mem_backend";
    private static final String KEY_LOCAL_PATH = "mem_local_path";
    private static final String KEY_TURN = "mem_turn_counter";
    private static final int SYNC_EVERY_TURNS = 100;

    private static final String GITHUB_PAT = "mem_github_pat";
    private static final String GITHUB_REPO = "mem_github_repo";
    private static final String GITHUB_PATH = "mem_github_path";
    private static final String DRIVE_TOKEN = "mem_drive_token";
    private static final String DRIVE_FOLDER = "mem_drive_folder_id";

    private MemoryManager() {}

    // ---- config -------------------------------------------------------

    public static String backend(Context c) {
        return EnginePrefs.prefs(c).getString(KEY_BACKEND, "local");
    }

    public static void setBackend(Context c, String b) {
        EnginePrefs.prefs(c).edit().putString(KEY_BACKEND, b).apply();
    }

    public static File localDir(Context c) {
        String p = EnginePrefs.prefs(c).getString(KEY_LOCAL_PATH, "");
        if (p != null && !p.trim().isEmpty()) {
            File f = new File(p.trim());
            if (f.isAbsolute()) {
                return f;
            }
        }
        return new File(c.getApplicationContext().getFilesDir(), "memory");
    }

    public static void setLocalPath(Context c, String path) {
        EnginePrefs.prefs(c).edit().putString(KEY_LOCAL_PATH, path).apply();
    }

    // ---- core --------------------------------------------------------

    /** Transcript file for an agent ("default" for the main agent). */
    private static File transcriptFile(Context c, String agentId) {
        File d = new File(localDir(c), agentId == null ? "default" : agentId);
        d.mkdirs();
        return new File(d, "transcript.md");
    }

    /** Append one turn (role: "You"/"Agent") and auto-sync policy. */
    public static void appendTurn(Context c, String agentId, String role, String text) {
        String line = "- **" + role + "** (" + now() + "): " + text + "\n";
        File f = transcriptFile(c, agentId);
        try {
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "append failed: " + e.getMessage());
        }
        int turns = EnginePrefs.prefs(c).getInt(KEY_TURN, 0) + 1;
        EnginePrefs.prefs(c).edit().putInt(KEY_TURN, turns).apply();
        if (turns % SYNC_EVERY_TURNS == 0) {
            syncNow(c, null);
        }
    }

    /** Keyword search over the local transcript; returns matching lines. */
    public static List<String> search(Context c, String agentId, String keyword) {
        List<String> out = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String needle = keyword.toLowerCase(Locale.US);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(transcriptFile(c, agentId)), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.toLowerCase(Locale.US).contains(needle)) {
                    out.add(line);
                    if (out.size() >= 100) {
                        break;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return out;
    }

    public static String readAll(Context c, String agentId) {
        return readFile(transcriptFile(c, agentId));
    }

    /** Push the current transcript to the configured remote backend. */
    public static void syncNow(final Context c, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                boolean ok;
                String backend = backend(app);
                try {
                    if ("github".equals(backend)) {
                        syncGithub(app);
                        result = "github sync ok";
                    } else if ("drive".equals(backend)) {
                        syncDrive(app);
                        result = "drive sync ok";
                    } else {
                        result = "local-only backend, nothing to sync";
                    }
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                    result = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                if (cb != null) {
                    final boolean fOk = ok;
                    final String fMsg = result;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            cb.onResult(fOk, fMsg);
                        }
                    });
                }
            }
        }, "mem-sync").start();
    }

    /** Fetch remote transcript into the local file (pull from backend). */
    public static void pullNow(final Context c, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok;
                String result;
                try {
                    String backend = backend(app);
                    if ("github".equals(backend)) {
                        pullGithub(app);
                        result = "github pull ok";
                    } else if ("drive".equals(backend)) {
                        pullDrive(app);
                        result = "drive pull ok";
                    } else {
                        result = "local-only backend, nothing to pull";
                    }
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                    result = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                if (cb != null) {
                    final boolean fOk = ok;
                    final String fMsg = result;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            cb.onResult(fOk, fMsg);
                        }
                    });
                }
            }
        }, "mem-pull").start();
    }

    // ---- github -------------------------------------------------------

    private static String githubPath(Context c) {
        String p = AuthStore.get(c, GITHUB_PATH);
        return (p == null || p.trim().isEmpty()) ? "svn-agent-memory.md" : p.trim();
    }

    private static void syncGithub(Context c) throws Exception {
        String pat = AuthStore.get(c, GITHUB_PAT);
        String repo = AuthStore.get(c, GITHUB_REPO);
        if (pat == null || repo == null) {
            throw new Exception("github backend needs mem_github_pat and mem_github_repo in Auths");
        }
        String path = githubPath(c);
        String content = readFile(transcriptFile(c, "default"));

        String sha = null;
        try {
            JSONObject existing = ghGet(pat, "https://api.github.com/repos/" + repo
                    + "/contents/" + path);
            sha = existing.optString("sha", null);
        } catch (Exception notFound) {
            // file does not exist yet — create
        }

        JSONObject body = new JSONObject();
        body.put("message", "svn-agent memory sync");
        body.put("content", Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP));
        if (sha != null) {
            body.put("sha", sha);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + repo + "/contents/" + path).openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "Bearer " + pat);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        byte[] payload = body.toString().getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(payload.length);
        OutputStream os = conn.getOutputStream();
        os.write(payload);
        os.close();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("github HTTP " + code + ": " + readAll(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        }
        conn.disconnect();
    }

    private static void pullGithub(Context c) throws Exception {
        String pat = AuthStore.get(c, GITHUB_PAT);
        String repo = AuthStore.get(c, GITHUB_REPO);
        if (pat == null || repo == null) {
            throw new Exception("github backend needs mem_github_pat and mem_github_repo in Auths");
        }
        JSONObject o = ghGet(pat, "https://api.github.com/repos/" + repo
                + "/contents/" + githubPath(c));
        String b64 = o.optString("content", "").replaceAll("\\s", "");
        if (b64.isEmpty()) {
            throw new Exception("remote file empty or not found");
        }
        String remote = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
        FileOutputStream out = new FileOutputStream(transcriptFile(c, "default"));
        out.write(remote.getBytes("UTF-8"));
        out.close();
    }

    private static JSONObject ghGet(String pat, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + pat);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        String raw = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("github HTTP " + code + ": " + raw);
        }
        return new JSONObject(raw);
    }

    // ---- drive --------------------------------------------------------

    private static void syncDrive(Context c) throws Exception {
        String token = AuthStore.get(c, DRIVE_TOKEN);
        if (token == null) {
            throw new Exception("drive backend needs mem_drive_token in Auths");
        }
        String folder = AuthStore.get(c, DRIVE_FOLDER);
        String content = readFile(transcriptFile(c, "default"));

        // find existing file id
        String q = "name='svn-agent-memory.md' and trashed=false";
        if (folder != null && !folder.isEmpty()) {
            q += " and '" + folder + "' in parents";
        }
        HttpURLConnection s = (HttpURLConnection) new URL(
                "https://www.googleapis.com/drive/v3/files?q="
                        + java.net.URLEncoder.encode(q, "UTF-8")
                        + "&fields=files(id)").openConnection();
        s.setRequestProperty("Authorization", "Bearer " + token);
        int scode = s.getResponseCode();
        String sraw = readAll(s.getInputStream());
        s.disconnect();
        String fileId = null;
        if (scode >= 200 && scode < 300) {
            JSONArray files = new JSONObject(sraw).optJSONArray("files");
            if (files != null && files.length() > 0) {
                fileId = files.getJSONObject(0).optString("id", null);
            }
        }

        String boundary = "svn_mem_" + Long.toHexString(System.nanoTime());
        JSONObject meta = new JSONObject();
        meta.put("name", "svn-agent-memory.md");
        if (fileId == null && folder != null && !folder.isEmpty()) {
            meta.put("parents", new JSONArray().put(folder));
        }
        String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + meta + "\r\n--" + boundary + "\r\nContent-Type: text/markdown\r\n\r\n"
                + content + "\r\n--" + boundary + "--";

        URL url = (fileId == null)
                ? new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                : new URL("https://www.googleapis.com/upload/drive/v3/files/" + fileId
                        + "?uploadType=multipart");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(fileId == null ? "POST" : "PATCH");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        conn.setDoOutput(true);
        byte[] payload = body.getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(payload.length);
        OutputStream os = conn.getOutputStream();
        os.write(payload);
        os.close();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("drive HTTP " + code + ": " + readAll(conn.getErrorStream()));
        }
        conn.disconnect();
    }

    private static void pullDrive(Context c) throws Exception {
        String token = AuthStore.get(c, DRIVE_TOKEN);
        if (token == null) {
            throw new Exception("drive backend needs mem_drive_token in Auths");
        }
        HttpURLConnection s = (HttpURLConnection) new URL(
                "https://www.googleapis.com/drive/v3/files?q="
                        + java.net.URLEncoder.encode(
                                "name='svn-agent-memory.md' and trashed=false", "UTF-8")
                        + "&fields=files(id)").openConnection();
        s.setRequestProperty("Authorization", "Bearer " + token);
        String raw = readAll(s.getInputStream());
        s.disconnect();
        JSONArray files = new JSONObject(raw).optJSONArray("files");
        if (files == null || files.length() == 0) {
            throw new Exception("no remote memory file found");
        }
        String fileId = files.getJSONObject(0).getString("id");
        HttpURLConnection d = (HttpURLConnection) new URL(
                "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media")
                .openConnection();
        d.setRequestProperty("Authorization", "Bearer " + token);
        String remote = readAll(d.getInputStream());
        d.disconnect();
        FileOutputStream out = new FileOutputStream(transcriptFile(c, "default"));
        out.write(remote.getBytes("UTF-8"));
        out.close();
    }

    // ---- util ---------------------------------------------------------

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    private static String readFile(File f) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }
}
