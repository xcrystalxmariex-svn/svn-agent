package com.svndev.svnagent;

/**
 * VoiceTextCleaner — strips markdown/code formatting from engine output so the
 * TTS engine doesn't literally read "backtick backtick backtick fun main" out
 * loud. The opencode engine streams markdown-heavy replies; anything headed to
 * the voice layer passes through {@link #clean(String)} first.
 *
 * Rules (order matters):
 *  1. Drop fenced code blocks entirely (``` ... ```); replace with "code
 *     omitted" so the user knows something was elided.
 *  2. Inline code &rarr; plain content (no backticks spoken).
 *  3. Markdown links [text](url) &rarr; text.
 *  4. Heading / emphasis / list punctuation (#, **, *, -, &gt;) removed.
 *  5. Collapse whitespace; cap length so long code replies stay speakable.
 */
public final class VoiceTextCleaner {

    private static final int MAX_SPOKEN_CHARS = 800;

    private VoiceTextCleaner() {}

    /** Convert engine markdown into clean, TTS-safe plain text. */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;

        // 1. Fenced code blocks -> " (code omitted) "
        s = s.replaceAll("(?s)```[\\w-]*\\n?.*?```", " code omitted. ");

        // 2. Inline code: drop the backticks, keep the content
        s = s.replaceAll("`([^`]*)`", "$1");

        // 3. Markdown links/images: keep the visible text
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");

        // 4. Heading/bold/italic/list formatting
        s = s.replaceAll("(?m)^\\s*#{1,6}\\s+", "");
        s = s.replaceAll("\\*\\*([^*]*)\\*\\*", "$1");
        s = s.replaceAll("(?m)(^|[^*])\\*([^*]+)\\*", "$1$2");
        s = s.replaceAll("__([^_]*)__", "$1");
        s = s.replaceAll("~~([^~]*)~~", "$1");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");

        // 5. Collapse runs of blank lines and spaces
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.replaceAll("[ \\t]{2,}", " ");
        s = s.trim();

        if (s.isEmpty()) {
            return "Done.";
        }
        if (s.length() > MAX_SPOKEN_CHARS) {
            s = s.substring(0, MAX_SPOKEN_CHARS).trim();
            int lastSentence = Math.max(
                    Math.max(s.lastIndexOf('.'), s.lastIndexOf('!')), s.lastIndexOf('?'));
            if (lastSentence > MAX_SPOKEN_CHARS / 2) {
                s = s.substring(0, lastSentence + 1);
            }
            s += " That's the summary.";
        }
        return s;
    }
}
