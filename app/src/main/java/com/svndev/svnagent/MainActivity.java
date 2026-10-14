package com.svndev.svnagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

/**
 * App launcher: on first run, calls RuntimeBootstrap.setup() to spin up the
 * contained opencode agent (extract runtimes, write config, init). After
 * that, shows an app-drawer style grid of icons; tapping "UI" starts the
 * in-app terminal TUI bound to the running agent.
 */
public class MainActivity extends Activity {

    private GridLayout grid;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RuntimeBootstrap bootstrap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bootstrap = new RuntimeBootstrap(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(30), dp(20), dp(20));
        root.setBackgroundColor(0xFF050b08);

        TextView title = new TextView(this);
        title.setText("[ SVN // AGENT CORE ]");
        title.setTextColor(0xFF00ff66);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(title, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 20));

        status = new TextView(this);
        status.setTextColor(0xFF00aa44);
        status.setTextSize(12);
        status.setTypeface(android.graphics.Typeface.MONOSPACE);
        status.setText("initializing...");
        root.addView(status, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 16));

        grid = new GridLayout(this);
        grid.setColumnCount(3);
        root.addView(grid, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 30));

        setContentView(root);

        if (bootstrap.isReady()) {
            showDrawer();
        } else {
            bootstrapAsync();
        }
    }

    private void bootstrapAsync() {
        status.setText("BOOTSTRAP // first run — installing agent runtimes...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = null;
                String err = null;
                try {
                    out = bootstrap.setup();
                } catch (final IOException e) {
                    err = e.getMessage();
                }
                final String result = out;
                final String error = err;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            // Bundled node/python runtime is optional: the ENGINE
                            // (Shizuku) path is self-contained. Don't block the drawer.
                            status.setText("agent ready (bundled runtime skipped: " + error + ")");
                        } else {
                            status.setText("BOOTSTRAP OK // runtime " + (result == null ? "" : result.trim()));
                        }
                        showDrawer();
                    }
                });
            }
        }).start();
    }

    private void showDrawer() {
        status.setText("agent ready — select a mode:");
        grid.removeAllViews();

        addItem("UI", "opencode web UI", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, WebUiActivity.class);
                startActivity(i);
            }
        });

        addItem("ENGINE", "Shizuku opencode console", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, EngineActivity.class);
                startActivity(i);
            }
        });

        addItem("VOICE", "device assistant", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, VoiceAssistantActivity.class);
                startActivity(i);
            }
        });

        addItem("⚙ SETTINGS", "config, runtime, reset", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(i);
            }
        });

        addItem("MCP/TOOLS", "servers + device tools", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, McpToolsActivity.class);
                startActivity(i);
            }
        });

        addItem("TERMUX", "local linux shell", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, TermuxActivity.class);
                startActivity(i);
            }
        });

        addItem("STATUS", "agent health readout", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("status @" + bootstrap.getHomeDir().getAbsolutePath());
                status.postDelayed(clearStatus, 4000);
            }
        });

        addItem("HELP", "how this agent works", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("ENGINE runs opencode on-device via Shizuku. SEE README.md.");
                status.postDelayed(clearStatus, 5000);
            }
        });

        addItem("CLEAN", "drop runtime, re-bootstrap", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grid.removeAllViews();
                status.setText("CLEAN // wiping runtime + config");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            bootstrap.clean();
                            bootstrap.setup();
                        } catch (Exception e) {
                            Log.w("MainActivity", "clean/setup: " + e.getMessage());
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("CLEAN OK");
                                showDrawer();
                            }
                        });
                    }
                }).start();
            }
        });

        addItem("ABOUT", "SVN Agent v1.0", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("SVN Agent v1.0 — contained agent core (Tron-green build)");
                status.postDelayed(clearStatus, 5000);
            }
        });

        addItem("IMPORT AGENT", "deploy a persona bundle", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, ImportAgentActivity.class);
                startActivity(i);
            }
        });

        PersonaImporter imp = new PersonaImporter(this);
        String[] ids = imp.getImportedIds();
        for (final String id : ids) {
            addItem("◉ " + id, "imported agent", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, TerminalActivity.class);
                    i.putExtra(TerminalActivity.EXTRA_HOME, imp.getHomeDirFor(id).getAbsolutePath());
                    startActivity(i);
                }
            });
        }
    }

    private final Runnable clearStatus = new Runnable() {
        @Override
        public void run() {
            status.setText("agent ready — select a mode:");
            if (grid.getChildCount() == 0) {
                MainActivity.this.showDrawer();
            }
        }
    };

    private void addItem(String label, String hint, View.OnClickListener listener) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(dp(8), dp(12), dp(8), dp(12));
        cell.setBackgroundResource(R.drawable.cell_border);

        TextView icon = new TextView(this);
        icon.setText(label);
        icon.setTextColor(0xFF00ff66);
        icon.setTextSize(22);
        icon.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        icon.setGravity(android.view.Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText(hint);
        sub.setTextColor(0xFF008844);
        sub.setTextSize(9);
        sub.setTypeface(android.graphics.Typeface.MONOSPACE);
        sub.setGravity(android.view.Gravity.CENTER);

        cell.addView(icon, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        cell.addView(sub, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(listener);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = dp(92);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        grid.addView(cell, lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int mb) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, 0);
        p.bottomMargin = dp(mb);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }
}