package com.svndev.svnagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VOICE panel — the device assistant's home.
 *
 * <p>Sections:
 * <ul>
 *   <li><b>Wake word</b> — always-listen toggle; when on, the wake-word setup
 *       (phrase + test) appears and {@link VoiceWakeService} runs.</li>
 *   <li><b>Agent voice</b> — pick the TTS voice and preview it.</li>
 *   <li><b>Live voice call</b> — a full-duplex-ish conversation with the agent:
 *       your speech is transcribed into a chat box, sent to the local engine
 *       ({@link EngineBrain}), and the reply is spoken back. A push-to-talk
 *       button is provided for walkie-talkie use.</li>
 * </ul>
 */
public class VoiceAssistantActivity extends Activity {

    public static final String EXTRA_AUTO_CALL = "auto_call";

    private static final int REQ_AUDIO = 4910;
    private static final int COLOR_FG = 0xFF00ff66;
    private static final int COLOR_DIM = 0xFF00aa44;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ScrollView rootScroll;
    private TextView wakeToggle;
    private LinearLayout wakeSetup;
    private TextView wakeStatus;
    private EditText wakePhrase;
    private TextView voiceLabel;
    private TextView callStatus;
    private TextView transcript;
    private TextView callButton;
    private TextView pttButton;

    private VoiceTts tts;
    private SpeechRecognizer recognizer;

    private boolean wakeEnabled;
    private boolean inCall;
    private volatile boolean speaking;
    private boolean listening;
    private boolean pttHeld;
    private boolean awaitingReply;
    private final StringBuilder transcriptBuf = new StringBuilder();

    private final Runnable clearStatus = new Runnable() {
        @Override public void run() {
            if (!inCall) {
                callStatus.setText("idle");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wakeEnabled = EnginePrefs.prefs(this).getBoolean(VoiceWakeService.PREF_ENABLED, false);
        buildUi();
        refreshWakeUi();

        tts = new VoiceTts(this);
        tts.init(new VoiceTts.Listener() {
            @Override public void onReady() {
                handler.post(new Runnable() {
                    @Override public void run() { refreshVoiceLabel(); }
                });
            }
        });

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_CALL, false)) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { startCall(); }
            }, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The UI owns the microphone while visible: pause wake-word listening.
        VoiceWakeService.setPaused(true);
        if (wakeEnabled) {
            VoiceWakeService.start(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the UI: hand the mic back to the wake-word service.
        VoiceWakeService.setPaused(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        if (tts != null) {
            tts.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(0xFF050b08);
        rootScroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(24), dp(14), dp(24));
        root.setGravity(Gravity.TOP);
        rootScroll.addView(root);

        TextView title = label("[ VOICE // DEVICE ASSISTANT ]", COLOR_FG, 16, true);
        root.addView(title, wrap(dp(4)));

        // ---- Wake word card ----
        LinearLayout wake = card("WAKE WORD");
        wakeToggle = greenButton("", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleWake(); }
        });
        wake.addView(wakeToggle, fullWidth(dp(46)));

        wakeStatus = label("", COLOR_DIM, 11, false);
        wakeStatus.setPadding(0, dp(6), 0, dp(6));
        wake.addView(wakeStatus);

        wakeSetup = new LinearLayout(this);
        wakeSetup.setOrientation(LinearLayout.VERTICAL);
        wake.addView(wakeSetup);

        wakeSetup.addView(label("Wake phrase", COLOR_DIM, 11, false), wrap(dp(2)));
        wakePhrase = new EditText(this);
        wakePhrase.setSingleLine(true);
        wakePhrase.setTextColor(COLOR_FG);
        wakePhrase.setHintTextColor(0xFF005522);
        wakePhrase.setTypeface(android.graphics.Typeface.MONOSPACE);
        wakePhrase.setText(EnginePrefs.prefs(this).getString(VoiceWakeService.PREF_PHRASE, VoiceWakeService.DEFAULT_PHRASE));
        wakeSetup.addView(wakePhrase, fullWidth(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout trainRow = new LinearLayout(this);
        trainRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView savePhrase = greenButton("SAVE PHRASE", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String p = wakePhrase.getText().toString().trim().toLowerCase(Locale.US);
                if (p.isEmpty()) {
                    p = VoiceWakeService.DEFAULT_PHRASE;
                }
                EnginePrefs.prefs(VoiceAssistantActivity.this).edit()
                        .putString(VoiceWakeService.PREF_PHRASE, p).apply();
                wakePhrase.setText(p);
                toast("wake phrase set to \"" + p + "\"");
            }
        });
        TextView testWake = greenButton("TEST WAKE", new View.OnClickListener() {
            @Override public void onClick(View v) { testWakePhrase(); }
        });
        trainRow.addView(savePhrase, weight());
        trainRow.addView(testWake, weight());
        wakeSetup.addView(trainRow, fullWidth(dp(46)));

        wakeSetup.addView(label(
                "Always-on listening runs a foreground service that keeps the mic on "
                        + "and waits for this phrase. Custom voice training is not yet "
                        + "wired to a wake-word engine — this build uses phrase spotting.",
                COLOR_DIM, 9, false), wrap(dp(6)));

        root.addView(wake, cardParams());

        // ---- Agent voice card ----
        LinearLayout voice = card("AGENT VOICE");
        voiceLabel = label("", COLOR_FG, 12, false);
        voice.addView(voiceLabel, wrap(dp(2)));

        LinearLayout voiceRow = new LinearLayout(this);
        voiceRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView pickBtn = greenButton("SELECT VOICE", new View.OnClickListener() {
            @Override public void onClick(View v) { pickVoice(); }
        });
        TextView previewBtn = greenButton("PREVIEW", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tts != null) {
                    tts.preview();
                }
            }
        });
        voiceRow.addView(pickBtn, weight());
        voiceRow.addView(previewBtn, weight());
        voice.addView(voiceRow, fullWidth(dp(46)));
        root.addView(voice, cardParams());

        // ---- Live call card ----
        LinearLayout call = card("LIVE VOICE CALL");
        callStatus = label("idle", COLOR_DIM, 11, false);
        call.addView(callStatus, wrap(dp(2)));

        transcript = new TextView(this);
        transcript.setTextColor(0xFF00dd55);
        transcript.setTextSize(12);
        transcript.setTypeface(android.graphics.Typeface.MONOSPACE);
        transcript.setPadding(dp(8), dp(8), dp(8), dp(8));
        transcript.setBackgroundColor(0xFF031007);
        transcript.setText("");
        call.addView(transcript, fullWidth(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        callButton = greenButton("START CALL", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (inCall) {
                    endCall();
                } else {
                    startCall();
                }
            }
        });
        call.addView(callButton, fullWidth(dp(48)));

        pttButton = greenButton("HOLD TO TALK", null);
        final View.OnTouchListener ptt = new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pttHeld = true;
                        tts.stop();
                        speaking = false;
                        startListening();
                        callStatus.setText("listening (hold)…");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (pttHeld) {
                            pttHeld = false;
                            stopListening();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        };
        pttButton.setOnTouchListener(ptt);
        call.addView(pttButton, fullWidth(dp(56)));

        root.addView(call, cardParams());
    }

    // ------------------------------------------------------------------
    // Wake word
    // ------------------------------------------------------------------

    private void toggleWake() {
        wakeEnabled = !wakeEnabled;
        EnginePrefs.prefs(this).edit().putBoolean(VoiceWakeService.PREF_ENABLED, wakeEnabled).apply();
        if (wakeEnabled) {
            if (!hasAudioPermission()) {
                requestAudio();
                // turn back off until granted
                wakeEnabled = false;
                EnginePrefs.prefs(this).edit().putBoolean(VoiceWakeService.PREF_ENABLED, false).apply();
            } else {
                VoiceWakeService.start(this);
            }
        } else {
            VoiceWakeService.stop(this);
        }
        refreshWakeUi();
    }

    private void refreshWakeUi() {
        wakeToggle.setText("WAKE WORD: " + (wakeEnabled ? "ON" : "OFF"));
        wakeSetup.setVisibility(wakeEnabled ? View.VISIBLE : View.GONE);
        if (wakeEnabled) {
            String phrase = EnginePrefs.prefs(this).getString(
                    VoiceWakeService.PREF_PHRASE, VoiceWakeService.DEFAULT_PHRASE);
            wakeStatus.setText("listening for \"" + phrase + "\" (always on)");
        } else {
            wakeStatus.setText("off");
        }
    }

    private void testWakePhrase() {
        if (!ensureAudio()) {
            return;
        }
        String phrase = wakePhrase.getText().toString().trim().toLowerCase(Locale.US);
        toast("say \"" + phrase + "\" now…");
        RecognitionListener probe = new RecognitionListener() {
            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
                boolean ok = text.toLowerCase(Locale.US).contains(
                        wakePhrase.getText().toString().trim().toLowerCase(Locale.US));
                toast(ok ? "HEARD wake phrase ✓ (\"" + text + "\")" : "heard: \"" + text + "\" (no match)");
                restoreListener();
            }
            @Override public void onError(int error) {
                toast("no speech detected");
                restoreListener();
            }
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}
        };
        startListeningWith(probe);
    }

    // ------------------------------------------------------------------
    // Voice picker
    // ------------------------------------------------------------------

    private void pickVoice() {
        if (tts == null || !tts.isReady()) {
            toast("voice engine still starting…");
            return;
        }
        final List<Voice> voices = tts.voices();
        if (voices.isEmpty()) {
            toast("no TTS voices installed");
            return;
        }
        final CharSequence[] labels = new CharSequence[voices.size()];
        for (int i = 0; i < voices.size(); i++) {
            labels[i] = VoiceTts.label(voices.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Agent voice")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        tts.selectVoice(voices.get(which));
                        refreshVoiceLabel();
                        tts.preview();
                    }
                })
                .show();
    }

    private void refreshVoiceLabel() {
        if (voiceLabel == null) {
            return;
        }
        String cur = tts == null ? "" : tts.savedVoiceName();
        voiceLabel.setText(cur == null || cur.isEmpty() ? "(system default voice)" : cur);
    }

    // ------------------------------------------------------------------
    // Live call
    // ------------------------------------------------------------------

    private void startCall() {
        if (!ensureAudio()) {
            return;
        }
        inCall = true;
        transcriptBuf.setLength(0);
        transcript.setText("");
        callButton.setText("DISCONNECT");
        callStatus.setText("connecting…");
        handler.removeCallbacks(clearStatus);
        tts.speak("Voice link established.", new VoiceTts.SpeakListener() {
            @Override public void onDone() {
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (inCall) {
                            startListening();
                        }
                    }
                });
            }
        });
    }

    private void endCall() {
        inCall = false;
        speaking = false;
        awaitingReply = false;
        stopListening();
        if (tts != null) {
            tts.stop();
        }
        callButton.setText("START CALL");
        callStatus.setText("disconnected");
        handler.removeCallbacks(clearStatus);
        handler.postDelayed(clearStatus, 4000);
    }

    private void handleHeard(String text) {
        listening = false;
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            restartIfInCall(600);
            return;
        }
        appendTranscript("You: " + t);
        callStatus.setText("thinking…");
        awaitingReply = true;
        EngineBrain.ask(this, t, new EngineBrain.Callback() {
            @Override public void onReply(final String reply) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        awaitingReply = false;
                        appendTranscript("Agent: " + reply);
                        callStatus.setText("speaking…");
                        speaking = true;
                        tts.speak(reply, new VoiceTts.SpeakListener() {
                            @Override public void onDone() {
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        speaking = false;
                                        if (inCall && !pttHeld) {
                                            startListening();
                                        } else if (!inCall) {
                                            callStatus.setText("idle");
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
            @Override public void onError(final String message) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        awaitingReply = false;
                        appendTranscript("[error] " + message);
                        callStatus.setText("engine error");
                        restartIfInCall(1500);
                    }
                });
            }
        });
    }

    private void restartIfInCall(int delayMs) {
        if (inCall && !pttHeld && !speaking) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (inCall && !listening && !speaking && !awaitingReply && !pttHeld) {
                        startListening();
                    }
                }
            }, delayMs);
        } else if (!inCall) {
            callStatus.setText("idle");
        }
    }

    private void appendTranscript(String line) {
        transcriptBuf.append(line).append("\n");
        transcript.setText(transcriptBuf.toString());
        rootScroll.post(new Runnable() {
            @Override public void run() { rootScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // ------------------------------------------------------------------
    // Speech recognition plumbing
    // ------------------------------------------------------------------

    private void ensureRecognizer() {
        if (recognizer != null) {
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(defaultListener);
    }

    private void restoreListener() {
        if (recognizer != null) {
            recognizer.setRecognitionListener(defaultListener);
        }
    }

    private final RecognitionListener defaultListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {
            callStatus.setText("listening…");
        }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rms) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle partial) {
            ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                callStatus.setText("hearing: " + list.get(0));
            }
        }
        @Override public void onEvent(int type, Bundle params) {}
        @Override public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
            handleHeard(text);
        }
        @Override public void onError(int error) {
            listening = false;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    callStatus.setText("microphone permission denied");
                    break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    restartIfInCall(400);
                    break;
                default:
                    restartIfInCall(800);
                    break;
            }
        }
    };

    private void startListening() {
        startListeningWith(defaultListener);
    }

    private void startListeningWith(RecognitionListener l) {
        if (!hasAudioPermission()) {
            return;
        }
        ensureRecognizer();
        recognizer.setRecognitionListener(l);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
        try {
            recognizer.startListening(i);
            listening = true;
        } catch (Exception e) {
            listening = false;
        }
    }

    private void stopListening() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
        }
        listening = false;
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private boolean hasAudioPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureAudio() {
        if (!hasAudioPermission()) {
            requestAudio();
            return false;
        }
        return true;
    }

    private void requestAudio() {
        requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO }, REQ_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "microphone granted" : "microphone permission required");
        }
    }

    // ------------------------------------------------------------------
    // small UI builders
    // ------------------------------------------------------------------

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.cell_border);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.addView(label(title, COLOR_FG, 13, true), wrap(dp(8)));
        return c;
    }

    private TextView label(String text, int color, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(android.graphics.Typeface.MONOSPACE,
                bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return t;
    }

    private TextView greenButton(String text, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF050b08);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(COLOR_FG);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        if (onClick != null) {
            b.setOnClickListener(onClick);
            b.setClickable(true);
        }
        return b;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(2), dp(6), dp(2), dp(6));
        return p;
    }

    private LinearLayout.LayoutParams fullWidth(int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        p.setMargins(0, dp(4), 0, dp(4));
        return p;
    }

    private LinearLayout.LayoutParams wrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottomMargin;
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
