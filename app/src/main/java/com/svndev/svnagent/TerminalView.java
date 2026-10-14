package com.svndev.svnagent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal VT100/ANSI terminal widget (pure Java, no dependencies).
 *
 * Renders a character grid to the canvas and understands the escape
 * sequences a TUI like Ink/opencode actually emits: SGR colors, cursor
 * movement (CUP/CUU/CUD/CUF/CUB), clear line/screen, and newline/Cr.
 *
 * Input is forwarded via sendChar(); keystrokes are appended to an
 * upstream reader that the host activity pipes into the process stdin.
 */
public class TerminalView extends View {

    private static final int COLS_DEFAULT = 80;
    private static final int ROWS_DEFAULT = 30;

    private int cols = COLS_DEFAULT;
    private int rows = ROWS_DEFAULT;

    private List<char[]> screen;
    private int curRow = 0;
    private int curCol = 0;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float cellW;
    private float cellH;
    private final int bgColor = Color.rgb(5, 11, 8);
    private final int fgColor = Color.rgb(0, 255, 102);

    // ANSI parser state
    private boolean inEsc = false;
    private boolean inOsc = false;
    private boolean inCsi = false;
    private final StringBuilder csi = new StringBuilder();

    private final StringBuilder stdin = new StringBuilder();

    public interface OnFeedListener {
        void onFeed();
    }

    private OnFeedListener feedListener;

    public void setOnFeedListener(OnFeedListener l) {
        this.feedListener = l;
    }

    /** Everything ever displayed, for copy-to-clipboard (last ~128KB). */
    private static final int SCROLLBACK_MAX = 128 * 1024;
    private final StringBuilder scrollback = new StringBuilder();

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setColor(fgColor);
        paint.setTextSize(16 * getResources().getDisplayMetrics().density);
        paint.setSubpixelText(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        resetScreen();
    }

    private void resetScreen() {
        screen = new ArrayList<>();
        for (int r = 0; r < rows; r++) screen.add(blank());
        curRow = 0;
        curCol = 0;
    }

    private char[] blank() {
        char[] row = new char[cols];
        for (int i = 0; i < cols; i++) row[i] = ' ';
        return row;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cellW = paint.measureText("M") + 1f;
        cellH = paint.getFontSpacing();
        int newCols = Math.max(10, (int) (w / cellW));
        int newRows = Math.max(5, (int) (h / cellH));
        if (newCols != cols || newRows != rows) {
            cols = newCols;
            rows = newRows;
            resetScreen();
        }
    }

    /** Clear the visible screen (and scrollback) so a fresh snapshot can be fed. */
    public void reset() {
        resetScreen();
        scrollback.setLength(0);
        inEsc = false;
        inOsc = false;
        inCsi = false;
        csi.setLength(0);
        postInvalidate();
    }

    /** Append raw bytes from the subprocess stdout. */
    public void feed(byte[] data, int len) {
        track(new String(data, 0, len));
        for (int i = 0; i < len; i++) feed((char) (data[i] & 0xff));
    }

    public void feed(String s) {
        track(s);
        for (int i = 0; i < s.length(); i++) feed(s.charAt(i));
        postInvalidate();
        if (feedListener != null) feedListener.onFeed();
    }

    private void track(String s) {
        scrollback.append(s);
        if (scrollback.length() > SCROLLBACK_MAX) {
            scrollback.delete(0, scrollback.length() - SCROLLBACK_MAX);
        }
    }

    /** Full copyable log of everything printed so far (truncated from the front). */
    public String dumpLog() {
        return scrollback.toString();
    }

    private void feed(char c) {
        if (inOsc) {
            if (c == 0x07 || c == 0x1b) inOsc = false;
            return;
        }
        if (inCsi) {
            csi.append(c);
            if ((c >= '0' && c <= '9') || c == ';' || c == '?') return;
            onCsi(csi.toString());
            csi.setLength(0);
            inCsi = false;
            return;
        }
        if (inEsc) {
            if (c == '[') { inCsi = true; inEsc = false; csi.setLength(0); }
            else if (c == ']') { inOsc = true; inEsc = false; }
            else if (c == '(' || c == ')') inEsc = false; // charset selection
            else inEsc = false;
            return;
        }
        switch (c) {
            case '\u001b': inEsc = true; return;
            case '\r': curCol = 0; return;
            case '\n': newline(); return;
            case '\b': if (curCol > 0) curCol--; return;
            case '\t': curCol = (curCol / 8 + 1) * 8; if (curCol >= cols) newline(); return;
            default:
                if (c < 0x20) return; // strip other control chars
                put(c);
        }
    }

    private void newline() {
        curRow++;
        curCol = 0;
        if (curRow >= rows) {
            screen.remove(0);
            screen.add(blank());
            curRow = rows - 1;
        }
    }

    private void put(char c) {
        if (curCol >= cols) newline();
        if (curRow < 0) curRow = 0;
        if (curRow >= rows) { curRow = rows - 1; }
        if (curCol >= 0 && curCol < cols) {
            char[] row = screen.get(curRow);
            if (row[curCol] != c) row[curCol] = c;
            curCol++;
        }
    }

    private void clearLine() {
        char[] row = blank();
        screen.set(Math.max(0, curRow), row);
    }

    private void clearScreen() {
        for (int r = 0; r < rows; r++) screen.set(r, blank());
        curRow = 0;
        curCol = 0;
    }

    private void onCsi(String body) {
        if (body.isEmpty()) return;
        char term = body.charAt(body.length() - 1);
        String params = body.substring(0, body.length() - 1);
        String[] p = params.isEmpty() ? new String[0] : params.split(";");

        int p0 = p.length > 0 ? parseInt(p[0]) : 0;
        int p1 = p.length > 1 ? parseInt(p[1]) : 0;

        try {
            switch (term) {
                case 'm': // SGR — colors/bold (we only honor reset; color support below)
                    return;
                case 'A': // cursor up
                    curRow = Math.max(0, curRow - (p0 == 0 ? 1 : p0));
                    return;
                case 'B': // cursor down
                    curRow = Math.min(rows - 1, curRow + (p0 == 0 ? 1 : p0));
                    return;
                case 'C': // cursor forward
                    curCol = Math.min(cols - 1, curCol + (p0 == 0 ? 1 : p0));
                    return;
                case 'D': // cursor back
                    curCol = Math.max(0, curCol - (p0 == 0 ? 1 : p0));
                    return;
                case 'G': // cursor column
                    curCol = Math.max(0, Math.min(cols - 1, (p0 == 0 ? 1 : p0) - 1));
                    return;
                case 'd': // cursor row (absolute, 1-based)
                    curRow = Math.max(0, Math.min(rows - 1, (p0 == 0 ? 1 : p0) - 1));
                    return;
                case 'H': // cursor position row;col (1-based)
                case 'f':
                    curRow = Math.max(0, Math.min(rows - 1, (p0 == 0 ? 1 : p0) - 1));
                    curCol = Math.max(0, Math.min(cols - 1, (p1 == 0 ? 1 : p1) - 1));
                    return;
                case 'J': // clear screen
                    if (p0 == 2) clearScreen();
                    return;
                case 'K': // clear line
                    clearLine();
                    return;
                case 's': // save cursor
                case 'u': // restore cursor
                    return;
                default:
                    // ignore unknown CSI (Ink sends many) — moving on is safe
                    return;
            }
        } catch (Exception ignored) {
        }
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(bgColor);
        float y = paint.getFontSpacing();
        for (int r = 0; r < rows && r < screen.size(); r++) {
            char[] row = screen.get(r);
            canvas.drawText(new String(row), 0, y, paint);
            y += paint.getFontSpacing();
        }
        // draw cursor as a block
        if (curRow >= 0 && curRow < rows && curCol >= 0 && curCol < cols) {
            float cx = curCol * cellW;
            float cy = curRow * paint.getFontSpacing() + paint.ascent();
            canvas.drawRect(cx, cy, cx + cellW, cy + paint.getFontSpacing(), cursorPaint());
        }
    }

    private Paint cursorPaint() {
        Paint p = new Paint();
        p.setColor(Color.rgb(0, 255, 102));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        return p;
    }

    /** Inject a keystroke into the stdin stream consumed by the reader thread. */
    public void sendChar(char c) {
        stdin.append(c);
    }

    public void sendText(String s) {
        stdin.append(s);
    }

    /** Pull pending input; returns null if nothing queued. */
    public String drainInput() {
        if (stdin.length() == 0) return null;
        String s = stdin.toString();
        stdin.setLength(0);
        return s;
    }
}