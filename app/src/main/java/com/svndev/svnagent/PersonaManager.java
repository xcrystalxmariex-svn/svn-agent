package com.svndev.svnagent;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Agent persona registry on top of {@link PersonaImporter}. A "persona" is a
 * manually created named profile (no bundle needed) that gets:
 *  - its own home under filesDir/agents/<id>/
 *  - its own transcript/memory folder via {@link MemoryManager}
 *  - a persona.json holding metadata (name, ai_model, session link, notes)
 *
 * Persona export produces a self-describing JSON "packet" that any other
 * agent/system (Gemini, ChatGPT, ...) can read to learn the import format.
 */
public final class PersonaManager {

    private PersonaManager() {}

    /** Create or update a persona profile. Returns the persona id. */
    public static String register(Context c, String name, String aiModel, String notes) {
        String id = slug(name == null ? "" : name);
        if (id.isEmpty()) {
            id = "persona-" + Long.toHexString(System.currentTimeMillis());
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name == null ? id : name);
            o.put("ai_model", aiModel == null ? "" : aiModel);
            o.put("notes", notes == null ? "" : notes);
            o.put("created", now());
            o.put("memory_agent_id", id); // MemoryManager folder key
            File home = new PersonaImporter(c).getHomeDirFor(id);
            home.mkdirs();
            FileOutputStream out = new FileOutputStream(new File(home, "persona.json"));
            out.write(o.toString(2).getBytes("UTF-8"));
            out.close();
            new PersonaImporter(c).registerId(id);
        } catch (Exception ignored) {}
        return id;
    }

    /** The exportable "packet" guide + template handed to foreign agents. */
    public static String packetTemplate() {
        return "{\n"
                + "  \"packet_format\": \"svn-agent-persona/v1\",\n"
                + "  \"instructions\": \"Fill in persona_name, ai_model, notes. "
                + "Return only this JSON object.\",\n"
                + "  \"persona_name\": \"<name the persona goes by>\",\n"
                + "  \"ai_model\": \"<e.g. gpt-4o, gemini-2.0, opus>\",\n"
                + "  \"traits\": [\"<short trait strings>\"],\n"
                + "  \"notes\": \"<freeform persona memory seed>\",\n"
                + "  \"home_files\": {\n"
                + "     \"AGENTS.md\": \"<optional instructions file contents>\"\n"
                + "  }\n"
                + "}\n";
    }

    /** Import a persona packet JSON string. Returns persona id or null. */
    public static String importPacket(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String name = o.optString("persona_name", o.optString("name", ""));
            String model = o.optString("ai_model", o.optString("model", ""));
            String notes = o.optString("notes", "");
            String id = register(c, name, model, notes);
            JSONObject files = o.optJSONObject("home_files");
            if (files != null) {
                File home = new PersonaImporter(c).getHomeDirFor(id);
                java.util.Iterator<String> it = files.keys();
                while (it.hasNext()) {
                    String fn = it.next();
                    if (fn.contains("/") || fn.contains("..")) {
                        continue;
                    }
                    FileOutputStream out = new FileOutputStream(new File(home, fn));
                    out.write(files.optString(fn, "").getBytes("UTF-8"));
                    out.close();
                }
            }
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private static String slug(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
