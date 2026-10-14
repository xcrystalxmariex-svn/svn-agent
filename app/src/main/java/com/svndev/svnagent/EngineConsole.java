package com.svndev.svnagent;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared in-app log for the opencode <b>engine</b> process (stdout/stderr plus
 * the prelaunch diagnostics we print: resolver refresh, net probe, local tools,
 * MCP config).
 *
 * <p>Purpose: the engine is owned by {@link EngineService} (a foreground service),
 * not by any Activity, so it keeps running when the user leaves the app. The open
 * {@link EngineActivity} merely <i>observes</i> this buffer — it renders
 * {@link #snapshot()} for history and subscribes as a {@link Sink} for live
 * updates, then unsubscribes in onPause. This is what lets the engine outlive its
 * window and keep its session when the user comes back.
 *
 * <p>All producers and the activity live in the same app process, so a static
 * buffer is sufficient (no IPC needed).
 */
public final class EngineConsole {

    /** Receives text as it is appended (called on the appending thread). */
    public interface Sink {
        void onAppend(String text);
    }

    private static final int MAX_CHARS = 256 * 1024;

    private static final StringBuilder BUFFER = new StringBuilder();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<Sink>();

    private EngineConsole() {
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

    /** Append a single line with a trailing newline (normalizes CR/LF noise). */
    public static void line(String text) {
        if (text == null) {
            return;
        }
        append(text);
        if (!text.endsWith("\n")) {
            append("\r\n");
        }
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
