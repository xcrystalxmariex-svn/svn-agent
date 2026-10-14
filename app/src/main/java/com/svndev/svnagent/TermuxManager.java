package com.svndev.svnagent;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridge to a locally-installed <b>Termux</b> app.
 *
 * <p>Two apps cannot share a shell/uid, so the only supported way for a normal
 * Android app to drive Termux is the {@code com.termux.RUN_COMMAND} intent,
 * handled by {@code com.termux.app.RunCommandService}. That requires:
 * <ol>
 *   <li>Termux installed,</li>
 *   <li>this app holding {@code com.termux.permission.RUN_COMMAND},</li>
 *   <li>{@code allow-external-apps=true} in {@code ~/.termux/termux.properties}
 *       inside Termux (a manual, one-time toggle Termux enforces for security).</li>
 * </ul>
 *
 * <p>{@link #runCommandSync} gives a one-shot, result-returning exec (used by the
 * agent's {@code termux_run} tool and by {@link TermuxSession} to launch a PTY
 * bridge). Results come back through a per-call {@link PendingIntent} broadcast.
 */
public final class TermuxManager {

    private static final String TAG = "TermuxManager";

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    public static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    public static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    public static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    // Termux returns RUN_COMMAND results via the plugin result mechanism: the
    // PendingIntent broadcast carries one Bundle extra keyed "result", whose
    // members use the unprefixed plugin keys (see TermuxConstants.TERMUX_SERVICE
    // EXTRA_PLUGIN_RESULT_BUNDLE* and TermuxPluginUtils.setPluginResultPendingIntentVariables).
    public static final String EXTRA_RESULT_BUNDLE = "result";
    public static final String EXTRA_RESULT_EXIT_CODE = "exitCode";
    public static final String EXTRA_RESULT_STDOUT = "stdout";
    public static final String EXTRA_RESULT_STDERR = "stderr";
    public static final String EXTRA_RESULT_ERRMSG = "errmsg";

    /** Termux's Linux prefix and home (stable locations inside the Termux sandbox). */
    public static final String PREFIX = "/data/data/com.termux/files/usr";
    public static final String HOME = "/data/data/com.termux/files/home";
    public static final String SHELL = PREFIX + "/bin/sh";

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private TermuxManager() {
    }

    /** Result of a one-shot RUN_COMMAND invocation. */
    public interface Result {
        void onResult(int exitCode, String stdout, String stderr, String errmsg);
    }

    public static boolean isInstalled(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            } else {
                pm.getPackageInfo(TERMUX_PACKAGE, 0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasRunCommandPermission(Context ctx) {
        try {
            return ctx.getPackageManager().checkPermission(PERMISSION_RUN_COMMAND, ctx.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Best-effort intent to install Termux (F-Droid app, then web). */
    public static Intent installIntent() {
        Intent market = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=" + TERMUX_PACKAGE));
        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return market;
    }

    public static Intent installWebIntent() {
        Intent web = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://f-droid.org/packages/" + TERMUX_PACKAGE + "/"));
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return web;
    }

    /** Launch the Termux app (so the user can set allow-external-apps / run pkg). */
    public static Intent openTermuxIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setPackage(TERMUX_PACKAGE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * Fire a RUN_COMMAND. When {@code cb} is non-null a result broadcast is
     * requested and {@code cb} is invoked (on a binder thread) with the output.
     *
     * @return true if the intent was handed to Termux, false on failure.
     */
    public static boolean runCommand(final Context ctx, String path, String[] args,
                                     String workdir, boolean background, final Result cb) {
        try {
            PendingIntent pi = null;
            if (cb != null) {
                final String action = ctx.getPackageName() + ".TERMUX_RESULT." + SEQ.incrementAndGet();
                BroadcastReceiver rec = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        try {
                            c.unregisterReceiver(this);
                        } catch (Exception ignored) {
                        }
                        int code = -1;
                        String out = "";
                        String err = "";
                        String em = "";
                        Bundle b = i.getBundleExtra(EXTRA_RESULT_BUNDLE);
                        if (b != null) {
                            code = b.getInt(EXTRA_RESULT_EXIT_CODE, -1);
                            out = b.getString(EXTRA_RESULT_STDOUT, "");
                            err = b.getString(EXTRA_RESULT_STDERR, "");
                            em = b.getString(EXTRA_RESULT_ERRMSG, "");
                        } else {
                            em = "no result bundle returned by Termux";
                        }
                        try {
                            cb.onResult(code, out, err, em);
                        } catch (Exception e) {
                            Log.w("TermuxManager", "result callback: " + e.getMessage());
                        }
                    }
                };
                IntentFilter f = new IntentFilter(action);
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(rec, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    ctx.registerReceiver(rec, f);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                Intent ri = new Intent(action).setPackage(ctx.getPackageName());
                pi = PendingIntent.getBroadcast(ctx, SEQ.incrementAndGet(), ri, flags);
            }

            Intent i = new Intent();
            i.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
            i.setAction(ACTION_RUN_COMMAND);
            i.putExtra(EXTRA_PATH, path);
            if (args != null) {
                i.putExtra(EXTRA_ARGUMENTS, args);
            }
            if (workdir != null && !workdir.isEmpty()) {
                i.putExtra(EXTRA_WORKDIR, workdir);
            }
            i.putExtra(EXTRA_BACKGROUND, background);
            i.putExtra(EXTRA_COMMAND_LABEL, "SVN Agent");
            i.putExtra(EXTRA_SESSION_ACTION, "0");
            if (pi != null) {
                i.putExtra(EXTRA_PENDING_INTENT, pi);
            }
            ctx.startService(i);
            return true;
        } catch (Exception e) {
            Log.w("TermuxManager", "runCommand: " + e.getMessage());
            return false;
        }
    }

    /**
     * Blocking one-shot shell invocation.
     *
     * @return {@code [exitCode, stdout, stderr, errmsg]} (exitCode "-1" on failure).
     */
    public static String[] runShellSync(Context ctx, String command, long timeoutMs) {
        if (!isInstalled(ctx)) {
            return new String[]{"-1", "", "", "Termux is not installed"};
        }
        if (!hasRunCommandPermission(ctx)) {
            return new String[]{"-1", "", "", "Termux RUN_COMMAND permission not granted"};
        }
        return runCommandSync(ctx, SHELL, new String[]{"-c", command}, null, timeoutMs);
    }

    /** Blocking RUN_COMMAND with a resolved {@code path} + {@code args}. */
    public static String[] runCommandSync(Context ctx, String path, String[] args,
                                          String workdir, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] box = new String[]{"-1", "", "", ""};
        Result cb = new Result() {
            @Override
            public void onResult(int exitCode, String stdout, String stderr, String errmsg) {
                box[0] = String.valueOf(exitCode);
                box[1] = stdout == null ? "" : stdout;
                box[2] = stderr == null ? "" : stderr;
                box[3] = errmsg == null ? "" : errmsg;
                latch.countDown();
            }
        };
        if (!runCommand(ctx, path, args, null, false, cb)) {
            return new String[]{"-1", "", "", "failed to start RUN_COMMAND"};
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return new String[]{"-1", "", "", "timed out after " + timeoutMs + "ms"};
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new String[]{"-1", "", "", "interrupted"};
        }
        return new String[]{
                box[0] == null ? "-1" : box[0],
                box[1] == null ? "" : box[1],
                box[2] == null ? "" : box[2],
                box[3] == null ? "" : box[3]
        };
    }
}
