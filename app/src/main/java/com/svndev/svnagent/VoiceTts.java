package com.svndev.svnagent;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Thin wrapper over Android {@link TextToSpeech} for the Voice Assistant: lists
 * the device's installed voices, lets one be selected (persisted), previews it,
 * and speaks agent replies. Init is asynchronous, so callers pass a callback.
 */
public final class VoiceTts {

    private static final String PREF_VOICE = "voice_tts_name";
    private static final String UTTER_AGENT = "svn-agent-reply";

    public interface Listener {
        void onReady();
    }

    /** Notified when an utterance finishes (used to resume listening). */
    public interface SpeakListener {
        void onDone();
    }

    private final Context app;
    private TextToSpeech tts;
    private volatile boolean ready;
    private SpeakListener speakListener;

    public VoiceTts(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    public void init(final Listener l) {
        tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ready = (status == TextToSpeech.SUCCESS);
                if (ready) {
                    applySavedVoice();
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onDone(String id) {
                            if (UTTER_AGENT.equals(id) && speakListener != null) {
                                speakListener.onDone();
                            }
                        }
                        @Override public void onError(String id) {
                            if (UTTER_AGENT.equals(id) && speakListener != null) {
                                speakListener.onDone();
                            }
                        }
                    });
                }
                if (l != null) {
                    l.onReady();
                }
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

    /** Human-readable voice list for the picker (name + locale). */
    public List<Voice> voices() {
        List<Voice> out = new ArrayList<>();
        if (tts == null) {
            return out;
        }
        try {
            for (Voice v : tts.getVoices()) {
                if (v != null) {
                    out.add(v);
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(out, new Comparator<Voice>() {
            @Override public int compare(Voice a, Voice b) {
                int c = a.getLocale().toString().compareTo(b.getLocale().toString());
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    public static String label(Voice v) {
        return v.getName() + "  [" + v.getLocale() + "]";
    }

    public String savedVoiceName() {
        return EnginePrefs.prefs(app).getString(PREF_VOICE, "");
    }

    /** Select a voice by name and persist it. */
    public void selectVoice(Voice v) {
        if (v == null || tts == null) {
            return;
        }
        try {
            tts.setVoice(v);
            EnginePrefs.prefs(app).edit().putString(PREF_VOICE, v.getName()).apply();
        } catch (Exception ignored) {}
    }

    private void applySavedVoice() {
        String name = savedVoiceName();
        if (name.isEmpty()) {
            try { tts.setLanguage(Locale.US); } catch (Exception ignored) {}
            return;
        }
        for (Voice v : voices()) {
            if (v.getName().equals(name)) {
                try { tts.setVoice(v); } catch (Exception ignored) {}
                return;
            }
        }
        try { tts.setLanguage(Locale.US); } catch (Exception ignored) {}
    }

    /** Speak agent text; listener (optional) fires when it finishes. */
    public void speak(String text, SpeakListener listener) {
        this.speakListener = listener;
        if (tts == null || text == null || text.isEmpty()) {
            if (listener != null) {
                listener.onDone();
            }
            return;
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, (Bundle) null, UTTER_AGENT);
        } catch (Exception e) {
            if (listener != null) {
                listener.onDone();
            }
        }
    }

    /** Preview the currently selected voice with a short sample. */
    public void preview() {
        if (tts == null) {
            return;
        }
        try {
            tts.speak("This is the voice of your device assistant.",
                    TextToSpeech.QUEUE_FLUSH, (Bundle) null, "svn-preview");
        } catch (Exception ignored) {}
    }

    public void stop() {
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
    }

    public void shutdown() {
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
            ready = false;
        }
    }
}
