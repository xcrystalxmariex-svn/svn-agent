package com.svndev.svnagent;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Interactive Termux session.
 *
 * <p>Since the app cannot share Termux's shell, we ask Termux (via
 * {@link TermuxManager#runCommand} with {@code background=true}) to start a
 * <b>socat</b> listener that exposes a login shell on loopback:
 *
 * <pre>socat TCP-LISTEN:&lt;port&gt;,bind=127.0.0.1,reuseaddr,fork \
 *       EXEC:'&lt;prefix&gt;/bin/bash -li',pty,stderr,setsid,sigint,sane</pre>
 *
 * <p>The app then connects an ordinary {@link Socket} to {@code 127.0.0.1:port},
 * giving a live PTY-backed shell: {@link #send} writes to the socket and the
 * reader thread streams output back to the host UI. Requires {@code socat}
 * (installed on demand with {@code pkg install -y socat}).
 */
public final class TermuxSession {

    public interface Listener {
        void onOutput(byte[] data, int len);

        void onClosed(String reason);

        void onReady();
    }

    private final Context ctx;
    private Socket socket;
    private OutputStream out;
    private Thread reader;
    private volatile boolean open;
    private int port;

    public TermuxSession(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isConnected() {
        return open;
    }

    public int getPort() {
        return port;
    }

    /**
     * Ensure socat, launch the loopback PTY listener in Termux, then connect.
     * Blocking; call off the UI thread.
     */
    public boolean start(final int port, final Listener cb) {
        this.port = port;

        String[] chk = TermuxManager.runShellSync(ctx, "command -v socat", 15000);
        boolean haveSocat = chk[1] != null && chk[1].trim().length() > 0;
        if (!haveSocat) {
            cb.onOutput(("installing socat (first run)...\n").getBytes(), 30);
            TermuxManager.runShellSync(ctx, "pkg install -y socat", 180000);
            chk = TermuxManager.runShellSync(ctx, "command -v socat", 15000);
            haveSocat = chk[1] != null && chk[1].trim().length() > 0;
            if (!haveSocat) {
                cb.onClosed("socat could not be installed (check Termux network / storage)");
                return false;
            }
        }

        String socat = TermuxManager.PREFIX + "/bin/socat";
        String bash = TermuxManager.PREFIX + "/bin/bash";
        String listen = "TCP-LISTEN:" + port + ",bind=127.0.0.1,reuseaddr,fork";
        String exec = "EXEC:" + bash + " -li,pty,stderr,setsid,sigint,sane";
        boolean started = TermuxManager.runCommand(ctx, socat,
                new String[]{listen, exec}, TermuxManager.HOME, true, null);
        if (!started) {
            cb.onClosed("could not start Termux socat listener");
            return false;
        }

        Socket s = null;
        for (int i = 0; i < 20 && !open; i++) {
            s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                socket = s;
                out = s.getOutputStream();
                open = true;
            } catch (Exception e) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!open) {
            cb.onClosed("no answer from 127.0.0.1:" + port + " (socat may be blocked)");
            return false;
        }

        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    InputStream in = socket.getInputStream();
                    int n;
                    while (open && (n = in.read(buf)) > 0) {
                        cb.onOutput(buf, n);
                    }
                } catch (Exception e) {
                    Log.w("TermuxSession", "reader: " + e.getMessage());
                } finally {
                    if (open) {
                        open = false;
                        cb.onClosed("session closed");
                    }
                }
            }
        }, "termux-session-reader");
        reader.setDaemon(true);
        reader.start();
        cb.onReady();
        return true;
    }

    /** Send text to the shell (add "\n" to submit a line). */
    public void send(String s) {
        if (!open || out == null) {
            return;
        }
        try {
            out.write(s.getBytes());
            out.flush();
        } catch (Exception e) {
            Log.w("TermuxSession", "send: " + e.getMessage());
        }
    }

    /** Close the connection (the socat child exits; listener keeps running). */
    public void stop() {
        open = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
        out = null;
    }

    /** Ask Termux to stop the socat listener for this port. */
    public void stopServer() {
        try {
            TermuxManager.runShellSync(ctx, "pkill -f 'TCP-LISTEN:" + port + "'", 10000);
        } catch (Exception ignored) {
        }
    }
}
