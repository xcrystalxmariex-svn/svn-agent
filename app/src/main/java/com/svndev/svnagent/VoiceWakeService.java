package com.svndev.svnagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Always-listening wake-word service. Runs in the foreground (microphone type)
 * so the OS keeps the mic open while the app is in the background, spots the
 * wake phrase (default "hey svn"), and then launches
 * {@link VoiceAssistantActivity} in call mode.
 *
 * <p>This first cut uses Android's on-device speech recognizer for phrase
 * spotting (restarting it after each utterance). That is functional but not a
 * purpose-built low-power wake engine — battery cost is real. A future build
 * can swap the {@link #startListening()} engine for Porcupine / openWakeWord
 * without touching the rest of the flow. While the Voice Assistant is in the
 * foreground, the service pauses so the two don't fight over the microphone.
 */
public class VoiceWakeService extends Service {

    private static final String TAG = "VoiceWakeService";

    public static final String PREF_ENABLED = "wake_enabled";
    public static final String PREF_PHRASE = "wake_phrase";
    public static final String DEFAULT_PHRASE = "hey svn";

    private static final String CHANNEL_ID = "svnagent_wake";
    private static final int NOTIF_ID = 0x5E18;

    /** Set true while the Voice Assistant UI is resumed (mic owned by the UI). */
    private static volatile boolean paused = false;
    private static volatile VoiceWakeService INSTANCE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean enabled;
    private String phrase = DEFAULT_PHRASE;

    /** Pause/resume listening; also used by the UI to yield the microphone. */
    public static void setPaused(boolean p) {
        paused = p;
        final VoiceWakeService s = INSTANCE;
        if (s != null) {
            s.handler.post(new Runnable() {
                @Override public void run() {
                    if (paused) {
                        s.stopListening();
                    } else if (s.enabled) {
                        s.startListening();
                    }
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        enabled = EnginePrefs.prefs(this).getBoolean(PREF_ENABLED, false);
        phrase = EnginePrefs.prefs(this).getString(PREF_PHRASE, DEFAULT_PHRASE).toLowerCase(Locale.US);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enabled = EnginePrefs.prefs(this).getBoolean(PREF_ENABLED, false);
        phrase = EnginePrefs.prefs(this).getString(PREF_PHRASE, DEFAULT_PHRASE).toLowerCase(Locale.US);
        if (!enabled || !hasAudio()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (!paused) {
            startListening();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
        stopListening();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    private void ensureRecognizer() {
        if (recognizer != null) {
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);
    }

    private void startListening() {
        if (!enabled || paused || !hasAudio()) {
            return;
        }
        ensureRecognizer();
        recognizer.setRecognitionListener(listener);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            scheduleRestart(1500);
        }
    }

    private void stopListening() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
        }
    }

    private void scheduleRestart(long delayMs) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (enabled && !paused) {
                    startListening();
                }
            }
        }, delayMs);
    }

    private void onHeard(String text) {
        if (text == null) {
            return;
        }
        String t = text.toLowerCase(Locale.US);
        if (!phrase.isEmpty() && t.contains(phrase)) {
            trigger();
        } else {
            scheduleRestart(250);
        }
    }

    private void trigger() {
        Log.i(TAG, "wake phrase detected");
        stopListening();
        Intent call = new Intent(this, VoiceAssistantActivity.class);
        call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        call.putExtra(VoiceAssistantActivity.EXTRA_AUTO_CALL, true);
        try {
            startActivity(call);
        } catch (Exception e) {
            Log.w(TAG, "could not launch assistant: " + e.getMessage());
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rms) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int type, Bundle params) {}
        @Override public void onPartialResults(Bundle partial) {
            ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                onHeard(list.get(0));
            }
        }
        @Override public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
            if (text.toLowerCase(Locale.US).contains(phrase)) {
                trigger();
            } else {
                scheduleRestart(250);
            }
        }
        @Override public void onError(int error) {
            scheduleRestart(error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? 300 : 1200);
        }
    };

    // ------------------------------------------------------------------

    private boolean hasAudio() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "SVN Agent wake word", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Always listening for the wake phrase.");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, VoiceAssistantActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("SVN Agent")
                .setContentText("Listening for \"" + phrase + "\"")
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    // ------------------------------------------------------------------

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, VoiceWakeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {}
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, VoiceWakeService.class));
        } catch (Throwable ignored) {}
    }
}
