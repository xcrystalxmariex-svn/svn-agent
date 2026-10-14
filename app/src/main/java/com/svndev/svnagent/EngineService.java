package com.svndev.svnagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * Foreground host for the opencode engine. This is what makes the engine
 * <b>persist while the user is away</b>.
 *
 * <p>Background: the engine is spawned via Shizuku as a child of the app
 * process, and its stdout/stderr are piped back into that process. Without a
 * foreground service the OS free-runs the app in the background and eventually
 * kills the process (LMK / cached-app reclaim); that tears down
 * {@link EngineLauncher} (a process-wide singleton) and the pipes, so the engine
 * dies and the next visit has to relaunch it — losing the session. Running this
 * service in the foreground keeps the whole process (singleton, pipes, local MCP
 * server, engine child) alive and at foreground priority.
 *
 * <p>The service owns the launch and prelaunch (resolver refresh, net probe,
 * local MCP server, MCP config) and re-attaches {@link EngineConsole} as the
 * output sink, so {@link EngineActivity} is free to come and go. If the engine
 * exits unexpectedly it is relaunched automatically (bounded) while the service
 * is alive.
 */
public class EngineService extends Service {

    private static final String TAG = "EngineService";

    public static final String ACTION_ENSURE = "com.svndev.svnagent.ENGINE_ENSURE";
    public static final String ACTION_STOP = "com.svndev.svnagent.ENGINE_STOP";

    private static final String PREFS = "svnagent";
    private static final String CHANNEL_ID = "svnagent_engine";
    private static final int NOTIF_ID = 0x5E17;

    /** Max consecutive auto-restarts before we give up and tell the user. */
    private static final int MAX_RESTARTS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EngineLauncher launcher = EngineLauncher.get();
    private EngineInstaller installer;

    /** True while the user/app wants the engine up; cleared on explicit stop. */
    private volatile boolean wantRunning = true;
    private int restarts = 0;

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        installer = new EngineInstaller(this);
        launcher.setExitListener(new EngineLauncher.ExitListener() {
            @Override
            public void onExit() {
                onEngineExit();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            wantRunning = false;
            launcher.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        wantRunning = true;
        ensureEngine();
        // START_STICKY: if the OS does kill us anyway, recreate the service.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // Service is going away (user stopped it or OS reclaim). Do NOT kill the
        // engine on a plain destroy — a sticky restart should find it alive.
        // Only an explicit ACTION_STOP tears it down.
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------
    // Engine bring-up
    // ---------------------------------------------------------------------

    /** Ensure the engine is (or becomes) running; safe to call repeatedly. */
    private void ensureEngine() {
        if (launcher.isRunning()) {
            EngineConsole.line("SVN // engine already running");
            return;
        }
        final int port = enginePort();
        final int mcpPort = localMcpPort();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EngineConsole.line(installer.refreshResolver());
                } catch (Throwable t) {
                    EngineConsole.line("resolver refresh skipped: " + t.getMessage());
                }

                // Diagnostic: test the engine's actual DNS + outbound path
                // (runs concurrently so it never delays startup).
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        EngineConsole.line("SVN // net check…");
                        EngineConsole.append(launcher.probe("models.dev"));
                    }
                }, "netprobe").start();

                // App-hosted local MCP server (device tools) + the MCP config the
                // engine reads via OPENCODE_CONFIG (remote servers + local tools).
                String localUrl = null;
                try {
                    localUrl = LocalMcpServer.get().start(EngineService.this, mcpPort);
                    EngineConsole.line("SVN // local tools: "
                            + (localUrl == null ? "unavailable" : localUrl));
                } catch (Throwable t) {
                    EngineConsole.line("SVN // local tools failed: " + t.getMessage());
                }
                try {
                    McpConfigWriter.write(EngineService.this, localUrl);
                } catch (Throwable t) {
                    EngineConsole.line("SVN // mcp config write failed: " + t.getMessage());
                }

                EngineConsole.line("SVN // launching engine (web, 127.0.0.1:" + port + ")…");
                // Launch on the main thread (mirrors the previously-working path;
                // Shizuku.newProcess is the only binder call and is quick).
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        launchEngine(port);
                    }
                });
            }
        }, "engine-ensure").start();
    }

    /** Spawn (or restart) the engine process; output flows to EngineConsole. */
    private void launchEngine(int port) {
        try {
            // Output flows to EngineConsole (sink == null); the Activity
            // subscribes to the console instead of owning the sink.
            launcher.launch(port, null);
            restarts = 0;
        } catch (final IOException e) {
            EngineConsole.line("FATAL: " + e.getMessage());
            notifyText("Engine failed to start: " + e.getMessage());
        }
    }

    /** Engine process died. Relaunch (bounded) while we still want it up. */
    private void onEngineExit() {
        if (!wantRunning) {
            return;
        }
        if (restarts >= MAX_RESTARTS) {
            EngineConsole.line("SVN // engine exited repeatedly — not restarting. "
                    + "Reopen the engine screen to retry.");
            notifyText("Engine stopped. Tap to reopen.");
            return;
        }
        restarts++;
        EngineConsole.line("SVN // engine exited — restarting (" + restarts + "/" + MAX_RESTARTS + ")…");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wantRunning) {
                    ensureEngine();
                }
            }
        }, 4000);
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "SVN Agent engine",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Keeps the local opencode engine running in the background.");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }

        Intent open = new Intent(this, EngineActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("SVN Agent")
                .setContentText("Engine running")
                .setOngoing(true)
                .setContentIntent(contentIntent);
        return b.build();
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("SVN Agent")
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true);
        try {
            nm.notify(NOTIF_ID + 1, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "notify failed: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    private int enginePort() {
        String p = prefs().getString(SettingsActivity.KEY_ENGINE_PORT, "");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return NodeMobile.DEFAULT_ENGINE_PORT;
        }
    }

    private int localMcpPort() {
        String p = prefs().getString(SettingsActivity.KEY_LOCAL_MCP_PORT, "4097");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return 4097;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------
    // Static helpers
    // ---------------------------------------------------------------------

    /** Start (or ensure) the foreground engine service. */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, EngineService.class);
        i.setAction(ACTION_ENSURE);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Explicitly stop the engine and the service. */
    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, EngineService.class);
        i.setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Throwable ignored) {}
    }
}
