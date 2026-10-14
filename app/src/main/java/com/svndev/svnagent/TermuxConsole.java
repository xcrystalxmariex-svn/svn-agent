package com.svndev.svnagent;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared in-app log for everything that happens in Termux — both the agent's
 * {@code termux:run} tool calls and the user's interactive session / one-shot
 * commands.
 *
 * <p>Purpose: the agent runs Termux commands silently in the background (no
 * Termux UI is launched), and the user reviews the results later in the app's
 * own <b>TERMUX</b> console ({@link TermuxActivity}) instead of being yanked
 * back and forth to the real Termux app. This singleton is the single source of
 * truth for that window: producers {@link #append} text, and the open activity
 * subscribes as a {@link Sink} for live updates plus renders {@link #snapshot()}
 * for history.
 *
 * <p>All producers and {@link TermuxActivity} live in the same app process, so a
 * static buffer is sufficient (no IPC needed).
 */
public final class TermuxConsole {

    /** Receives text as it is appended (called on the appending thread). */
    public interface Sink {
        void onAppend(String text);
    }

    private static final int MAX_CHARS = 256 * 1024;

    private static final StringBuilder BUFFER = new StringBuilder();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<Sink>();

    private TermuxConsole() {
    }

    /** Append text to the shared log and fan it out to any subscribed sinks. */
    public static void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (BUFFER) {
            BUFFER.append(text);
            if (BUFFER.length() > MAX_CHARS) {
                BUFFER.delete(0, BUFFER.length() - MAX_CHARS);
            }
        }
        for (Sink s : SINKS) {
            try {
                s.onAppend(text);
            } catch (Exception ignored) {
            }
        }
    }

    /** Convenience: append a command invocation banner. */
    public static void command(String cmd) {
        append("\n$ " + cmd + "\n");
    }

    /** Convenience: append a one-shot result block. */
    public static void result(String exit, String stdout, String stderr, String errmsg) {
        if (stdout != null && !stdout.isEmpty()) {
            append(stdout);
            if (!stdout.endsWith("\n")) {
                append("\n");
            }
        }
        if (stderr != null && !stderr.isEmpty()) {
            append(stderr);
            if (!stderr.endsWith("\n")) {
                append("\n");
            }
        }
        if (errmsg != null && !errmsg.isEmpty()) {
            append("[termux] " + errmsg + "\n");
        }
        append("[exit " + exit + "]\n");
    }

    /** Full current log (truncated from the front at {@link #MAX_CHARS}). */
    public static String snapshot() {
        synchronized (BUFFER) {
            return BUFFER.toString();
        }
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.setLength(0);
        }
    }

    public static void addSink(Sink s) {
        if (s != null && !SINKS.contains(s)) {
            SINKS.add(s);
        }
    }

    public static void removeSink(Sink s) {
        SINKS.remove(s);
    }
}
