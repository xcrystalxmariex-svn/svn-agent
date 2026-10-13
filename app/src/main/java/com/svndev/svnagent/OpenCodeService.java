package com.svndev.svnagent;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Background host for the opencode agent process. Survives the activity,
 * so closing the terminal doesn't kill the agent mid-task. The terminal
 * attaches to the same process on next open.
 *
 * This service is currently a light wrapper: the process itself is spawned
 * from TerminalActivity (single-front-end model). It exists so we can later
 * move the spawn here (START_STICKY, reconnect) without changing the shell.
 */
public class OpenCodeService extends Service {

    private static final String TAG = "OpenCodeService";
    private Process openCodeProcess;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public Process spawnAgent() {
        RuntimeBootstrap boot = new RuntimeBootstrap(this);
        File home = boot.getHomeDir();
        File runtime = boot.getRuntimeDir();
        File node = new File(runtime, "node/bin/node");
        File opencode = new File(runtime, "opencode/bin/opencode");
        File py = new File(runtime, "python/bin/python");
        File agentPy = new File(runtime, "agent/main.py");
        if (!node.exists() && !py.exists()) {
            try {
                boot.setup();
            } catch (IOException e) {
                Log.e(TAG, "bootstrap failed", e);
                return null;
            }
        }
        try {
            ProcessBuilder pb;
            if (node.exists()) {
                pb = new ProcessBuilder(node.getAbsolutePath(), opencode.getAbsolutePath());
            } else {
                pb = new ProcessBuilder(py.getAbsolutePath(), agentPy.getAbsolutePath());
            }
            pb.environment().put("HOME", home.getAbsolutePath());
            pb.environment().put("SVN_HOME", home.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.directory(home);
            openCodeProcess = pb.start();
            return openCodeProcess;
        } catch (IOException e) {
            Log.e(TAG, "spawn failed", e);
            return null;
        }
    }

    @Override
    public void onDestroy() {
        if (openCodeProcess != null) {
            openCodeProcess.destroy();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}