package com.svndev.svnagent;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * In-app opencode web UI. The Shizuku engine runs {@code opencode web}, which
 * serves the browser client on {@code http://127.0.0.1:<port>/}; we load that
 * straight into an embedded {@link WebView} (no external browser). Basic auth is
 * supplied automatically from {@link EngineLauncher}.
 *
 * If the engine isn't running yet, an overlay routes the user to ENGINE to
 * provision/launch it; on return (onResume) the UI loads.
 */
public class WebUiActivity extends Activity {

    private static final String C = "svnagent";

    private WebView web;
    private LinearLayout overlay;
    private TextView overlayText;
    private TextView overlayAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050b08);

        web = new WebView(this);
        web.setBackgroundColor(0xFF050b08);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                  String host, String realm) {
                handler.proceed(EngineLauncher.SERVER_USER, EngineLauncher.SERVER_PASSWORD);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideOverlay();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    showOverlay("Could not reach the engine UI.\n\n" + err.getDescription(),
                            "RETRY", new View.OnClickListener() {
                                @Override public void onClick(View v) { load(); }
                            });
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xF2050b08);
        overlay.setPadding(dp(28), dp(28), dp(28), dp(28));

        overlayText = new TextView(this);
        overlayText.setTextColor(0xFF00ff66);
        overlayText.setTextSize(13);
        overlayText.setTypeface(android.graphics.Typeface.MONOSPACE);
        overlayText.setGravity(Gravity.CENTER);
        overlay.addView(overlayText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayAction = new TextView(this);
        overlayAction.setTextColor(0xFF050b08);
        overlayAction.setTextSize(12);
        overlayAction.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        overlayAction.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF00ff66);
        d.setCornerRadius(dp(4));
        overlayAction.setBackground(d);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        alp.topMargin = dp(20);
        overlay.addView(overlayAction, alp);

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** Keep same-origin (the engine) in the WebView; send everything else out. */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {}
        return true;
    }

    private int enginePort() {
        String p = getSharedPreferences(C, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_ENGINE_PORT, "");
        try {
            return Integer.parseInt(p.trim());
        } catch (Exception e) {
            return NodeMobile.DEFAULT_ENGINE_PORT;
        }
    }

    private String url() {
        return "http://127.0.0.1:" + enginePort() + "/";
    }

    private void load() {
        hideOverlay();
        if (!EngineLauncher.get().isRunning()) {
            showOverlay("The engine isn't running yet.\n\nProvision and start it once from ENGINE, "
                    + "then it stays up for this UI.", "OPEN ENGINE", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(WebUiActivity.this, EngineActivity.class));
                }
            });
            return;
        }
        web.loadUrl(url());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        load();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    private void showOverlay(String text, String actionLabel, View.OnClickListener action) {
        overlayText.setText(text);
        overlayAction.setText(actionLabel);
        overlayAction.setOnClickListener(action);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
