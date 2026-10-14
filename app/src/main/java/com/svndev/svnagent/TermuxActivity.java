package com.svndev.svnagent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * TERMUX panel: detect/install Termux, walk through the one-time permission
 * setup, then give an interactive in-app terminal backed by a real Termux
 * shell (see {@link TermuxSession}). Also the on-screen home of the agent's
 * {@code termux:run} local tool.
 */
public class TermuxActivity extends Activity {

    private static final int REQ_PERM = 4701;
    private static final int TERMUX_PORT = 9001;
    private static final int TERM_HEIGHT_DP = 250;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TerminalView term;
    private EditText input;
    private LinearLayout termBlock;
    private TermuxSession session;

    /** Live link to {@link TermuxConsole}: agent + session output appears here. */
    private final TermuxConsole.Sink sink = new TermuxConsole.Sink() {
        public void onAppend(final String text) {
            ui.post(new Runnable() {
                public void run() {
                    if (term != null) {
                        term.feed(text);
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF050b08);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("TERMUX // LOCAL LINUX");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(0xFF00cc55);
        status.setTextSize(12);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(btn("INSTALL TERMUX", new View.OnClickListener() {
            public void onClick(View v) {
                installTermux();
            }
        }));
        buttons.addView(btn("OPEN TERMUX (enable allow-external-apps)", new View.OnClickListener() {
            public void onClick(View v) {
                openTermux();
            }
        }));
        buttons.addView(btn("GRANT RUN_COMMAND PERMISSION", new View.OnClickListener() {
            public void onClick(View v) {
                requestRunCommandPermission();
            }
        }));
        buttons.addView(btn("TEST CONNECTION", new View.OnClickListener() {
            public void onClick(View v) {
                testConnection();
            }
        }));
        buttons.addView(btn("START / RESTART SESSION", new View.OnClickListener() {
            public void onClick(View v) {
                startSession();
            }
        }));
        buttons.addView(btn("RUN COMMAND (one-shot, no socat needed)", new View.OnClickListener() {
            public void onClick(View v) {
                String s = input.getText().toString().trim();
                if (s.isEmpty()) {
                    Toast.makeText(TermuxActivity.this, "type a command above", Toast.LENGTH_SHORT).show();
                    return;
                }
                input.setText("");
                runOneShot(s);
            }
        }));
        root.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText("one-time setup — in Termux run:\n"
                + "  mkdir -p ~/.termux\n"
                + "  echo allow-external-apps=true >> ~/.termux/termux.properties\n"
                + "  termux-reload-settings      (then force-stop & reopen Termux)\n"
                + "then grant the permission above. START SESSION installs socat on\n"
                + "first use (needs Termux network); RUN COMMAND works without it.");
        help.setTextColor(0xFF008844);
        help.setTextSize(10);
        help.setTypeface(android.graphics.Typeface.MONOSPACE);
        help.setPadding(0, dp(6), 0, dp(6));
        root.addView(help, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        termBlock = new LinearLayout(this);
        termBlock.setOrientation(LinearLayout.VERTICAL);

        // The terminal output box — fixed height so it cannot collapse to 0.
        ScrollView termScroll = new ScrollView(this);
        term = new TerminalView(this);
        termScroll.addView(term, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TERM_HEIGHT_DP)));
        termBlock.addView(termScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(TERM_HEIGHT_DP), 0f));

        // Ensure the terminal always scrolls to the bottom when new text arrives.
        term.setOnFeedListener(new TerminalView.OnFeedListener() {
            @Override
            public void onFeed() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        termScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });

        TextView terminalLabel = new TextView(this);
        terminalLabel.setText("TERMINAL OUTPUT");
        terminalLabel.setTextColor(0xFF008844);
        terminalLabel.setTextSize(10);
        terminalLabel.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        terminalLabel.setPadding(0, dp(6), 0, dp(2));
        termBlock.addView(terminalLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        input = new EditText(this);
        input.setHint("type a command, then send");
        input.setHintTextColor(0xFF008844);
        input.setTextColor(0xFF00ff66);
        input.setBackgroundColor(0xFF0a140d);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    submitInput();
                    return true;
                }
                return false;
            }
        });
        termBlock.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);

        shortcuts.addView(btn("CTRL-C", new View.OnClickListener() {
            public void onClick(View v) {
                if (session != null) session.send("\u0003");
            }
        }));
        shortcuts.addView(btn("TAB", new View.OnClickListener() {
            public void onClick(View v) {
                if (session != null) session.send("\t");
            }
        }));
        shortcuts.addView(btn("CLEAR", new View.OnClickListener() {
            public void onClick(View v) {
                if (session != null && session.isConnected()) {
                    session.send("clear\n");
                } else {
                    TermuxConsole.clear();
                    if (term != null) term.reset();
                }
            }
        }));
        shortcuts.addView(btn("EXIT", new View.OnClickListener() {
            public void onClick(View v) {
                if (session != null) session.send("exit\n");
            }
        }));

        termBlock.addView(shortcuts, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(termBlock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (term != null) {
            term.reset();
            term.feed(TermuxConsole.snapshot());
        }
        TermuxConsole.addSink(sink);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TermuxConsole.removeSink(sink);
    }

    private void refresh() {
        ui.post(new Runnable() {
            public void run() {
                refreshStatus();
            }
        });
    }

    private void refreshStatus() {
        boolean installed = TermuxManager.isInstalled(this);
        boolean perm = TermuxManager.hasRunCommandPermission(this);
        boolean up = session != null && session.isConnected();
        status.setText("termux: " + (installed ? "installed" : "NOT installed")
                + "  |  run-command perm: " + (perm ? "granted" : "missing")
                + "  |  session: " + (up ? "CONNECTED :" + TERMUX_PORT : "not started"));
    }

    private void installTermux() {
        try {
            startActivity(TermuxManager.installIntent());
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(TermuxManager.installWebIntent());
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No app store found — visit f-droid.org", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openTermux() {
        if (!TermuxManager.isInstalled(this)) {
            Toast.makeText(this, "Install Termux first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(TermuxManager.openTermuxIntent());
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Could not open Termux", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestRunCommandPermission() {
        if (!TermuxManager.isInstalled(this)) {
            Toast.makeText(this, "Install Termux first", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{TermuxManager.PERMISSION_RUN_COMMAND}, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "permission granted" : "permission denied", Toast.LENGTH_SHORT).show();
            refreshStatus();
        }
    }

    private void testConnection() {
        status.setText("testing…");
        final String cmd = "echo SVN_TERMUX_OK; id; uname -a";
        TermuxConsole.command(cmd);
        new Thread(new Runnable() {
            public void run() {
                final String[] r = TermuxManager.runShellSync(TermuxActivity.this, cmd, 20000);
                final String out = (r[1] == null ? "" : r[1].trim());
                final String err = (r[2] == null ? "" : r[2].trim());
                final String em = (r[3] == null ? "" : r[3].trim());
                TermuxConsole.result(r[0], r[1], r[2], r[3]);
                ui.post(new Runnable() {
                    public void run() {
                        boolean ok = out.contains("SVN_TERMUX_OK");
                        status.setText(ok ? "test: OK" : "test: FAILED");
                        if (!ok) {
                            Toast.makeText(TermuxActivity.this,
                                    "failed: " + (em.isEmpty() ? (err.isEmpty() ? out : err) : em),
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshStatus();
                    }
                });
            }
        }).start();
    }

    private void startSession() {
        if (!TermuxManager.isInstalled(this)) {
            Toast.makeText(this, "Install Termux first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TermuxManager.hasRunCommandPermission(this)) {
            Toast.makeText(this, "Grant RUN_COMMAND permission first", Toast.LENGTH_SHORT).show();
            return;
        }
        TermuxConsole.append("\n[svn-agent] starting Termux session on 127.0.0.1:" + TERMUX_PORT + " ...\n");
        if (session != null) {
            session.stopServer();
            session.stop();
        }
        session = new TermuxSession(this);
        final TermuxSession s = session;
        new Thread(new Runnable() {
            public void run() {
                boolean ok = session.start(TERMUX_PORT, new TermuxSession.Listener() {
                    public void onOutput(final byte[] data, final int len) {
                        TermuxConsole.append(new String(data, 0, len,
                                java.nio.charset.StandardCharsets.UTF_8));
                    }

                    public void onClosed(final String reason) {
                        TermuxConsole.append("\n[session closed: " + reason + "]\n");
                        ui.post(new Runnable() {
                            public void run() {
                                refreshStatus();
                            }
                        });
                    }

                    public void onReady() {
                        ui.post(new Runnable() {
                            public void run() {
                                refreshStatus();
                                input.requestFocus();
                            }
                        });
                    }
                });
                if (!ok) {
                    ui.post(new Runnable() {
                        public void run() {
                            refreshStatus();
                        }
                    });
                }
            }
        }, "termux-session-start").start();
    }

    private void submitInput() {
        String s = input.getText().toString();
        input.setText("");
        if (s.isEmpty()) return;

        TermuxConsole.command(s);
        if (session != null && session.isConnected()) {
            session.send(s + "\n");
        } else {
            runOneShot(s);
        }
    }

    /** Run a single command through RUN_COMMAND and print its output (no socat). */
    private void runOneShot(final String cmd) {
        if (!TermuxManager.isInstalled(this)) {
            Toast.makeText(this, "Install Termux first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TermuxManager.hasRunCommandPermission(this)) {
            Toast.makeText(this, "Grant RUN_COMMAND permission first", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String[] r = TermuxManager.runShellSync(TermuxActivity.this, cmd, 120000);
                TermuxConsole.result(r[0], r[1], r[2], r[3]);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.stop();
        }
    }

    private Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFF050b08);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF00cc55);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button btn(String label, View.OnClickListener l, int width, float weight) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFF050b08);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF00cc55);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
