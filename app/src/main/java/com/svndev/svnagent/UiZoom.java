package com.svndev.svnagent;

import android.app.Activity;
import android.content.Context;
import android.view.View;

/**
 * App-wide UI zoom. The user picks a scale factor (50%–150%) from the main
 * menu; it is persisted in SharedPreferences and applied to the root view of
 * every screen via {@link #apply(Activity)} (call at the end of onCreate
 * after setContentView). Everything the user sees — MainActivity grid, voice
 * panel, transcripts — scales together.
 */
public final class UiZoom {

    public static final float MIN = 0.5f;
    public static final float MAX = 1.5f;
    public static final float STEP = 0.1f;
    private static final String KEY = "ui_zoom_scale";

    private UiZoom() {}

    public static float get(Context c) {
        return EnginePrefs.prefs(c).getFloat(KEY, 1.0f);
    }

    public static void set(Context c, float scale) {
        float s = Math.max(MIN, Math.min(MAX, scale));
        EnginePrefs.prefs(c).edit().putFloat(KEY, s).apply();
    }

    /** Apply the persisted zoom to the activity's content view. */
    public static void apply(Activity a) {
        float s = get(a);
        View root = a.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        // Pivot top-left so the UI shrinks/grows from the corner rather than
        // center-clipping off screen.
        root.setPivotX(0f);
        root.setPivotY(0f);
        root.setScaleX(s);
        root.setScaleY(s);
    }

    public static String describe(Context c) {
        return Math.round(get(c) * 100f) + "%";
    }
}
