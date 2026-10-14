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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TerminalView term;
    private EditText input;
    private LinearLayout termBlock;
    private TermuxSession session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF050b08);
        root.setPadding(dp(14), dp(16), dp(14), dp(10));

        TextView title = new TextView(this);
        title.setText("TERMUX // LOCAL LINUX");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF00cc55);
        status.setTextSize(11);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setPadding(0, dp(6), 0, dp(8));
        root.addView(status);

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
        root.addView(buttons);

        TextView help = new TextView(this);
        help.setText("one-time setup — in Termux run:\n"
                + "  echo allow-external-apps=true >> ~/.termux/termux.properties\n"
                + "  termux-reload-settings\n"
                + "then grant the permission above and START SESSION.");
        help.setTextColor(0xFF008844);
        help.setTextSize(9);
        help.setTypeface(android.graphics.Typeface.MONOSPACE);
        help.setPadding(0, dp(8), 0, dp(8));
        root.addView(help);

        termBlock = new LinearLayout(this);
        termBlock.setOrientation(LinearLayout.VERTICAL);
        termBlock.setVisibility(View.GONE);

        ScrollView termScroll = new ScrollView(this);
        term = new TerminalView(this);
        termScroll.addView(term, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        termBlock.addView(termScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        input = new EditText(this);
        input.setHint("type a command, then send");
        input.setHintTextColor(0xFF008844);
        input.setTextColor(0xFF00ff66);
        input.setBackgroundColor(0xFF0a140d);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(13);
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
                if (session != null) session.send("clear\n");
            }
        }));
        shortcuts.addView(btn("EXIT", new View.OnClickListener() {
            public void onClick(View v) {
                if (session != null) session.send("exit\n");
            }
        }));
        termBlock.addView(shortcuts);

        root.addView(termBlock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
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
        new Thread(new Runnable() {
            public void run() {
                final String[] r = TermuxManager.runShellSync(
                        TermuxActivity.this, "echo SVN_TERMUX_OK; id; uname -a", 20000);
                ui.post(new Runnable() {
                    public void run() {
                        String out = (r[1] == null ? "" : r[1].trim());
                        String err = (r[2] == null ? "" : r[2].trim());
                        String em = (r[3] == null ? "" : r[3].trim());
                        if (out.contains("SVN_TERMUX_OK")) {
                            Toast.makeText(TermuxActivity.this, "OK: " + out.replace('\n', ' '), Toast.LENGTH_LONG).show();
                        } else {
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
        termBlock.setVisibility(View.VISIBLE);
        term.feed("\n[svn-agent] starting Termux session on 127.0.0.1:" + TERMUX_PORT + " …\n");
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
                        ui.post(new Runnable() {
                            public void run() {
                                term.feed(data, len);
                            }
                        });
                    }

                    public void onClosed(final String reason) {
                        ui.post(new Runnable() {
                            public void run() {
                                term.feed("\n[session closed: " + reason + "]\n");
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
        if (session == null || !session.isConnected()) {
            Toast.makeText(this, "start a session first", Toast.LENGTH_SHORT).show();
            return;
        }
        session.send(s + "\n");
        input.setText("");
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
        b.setTextSize(11);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFF050b08);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF00cc55);
        d.setCornerRadius(dp(4));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
