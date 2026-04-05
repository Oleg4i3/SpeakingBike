package com.velo.speedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.List;
import java.util.Locale;

/**
 * Scrollable real-time chart of raw accelerometer magnitude.
 *
 * Displays a sliding 10-second window of |accel| (m/s²).
 * User can pan/fling to scroll back through history.
 * "● LIVE" indicator appears when showing the latest data.
 */
public class AccelChartView extends View {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final float WINDOW_SEC   = 10f;   // seconds visible at once
    private static final float GRID_STEP    = 1f;    // vertical grid every 1 second
    private static final float GRAVITY      = 9.81f; // reference line

    // ── Data source ───────────────────────────────────────────────────────────
    /** Reference to CadenceDetector's rawSamples list. Synchronize on this before iterating. */
    private List<float[]> data = null;
    /** Time (sec) of the latest sample known to the chart */
    private float totalSec   = 0f;
    /** Time shown at the right edge of the chart */
    private float viewEndSec = WINDOW_SEC;
    /** If true, viewEndSec tracks the latest sample automatically */
    private boolean autoScroll = true;

    // ── Y-range (auto-ranging with lerp smoothing) ────────────────────────────
    private float yLo    = 7f;
    private float yHi    = 13f;
    private float yLoTgt = 7f;
    private float yHiTgt = 13f;

    // ── Paints (allocated once, never in onDraw) ──────────────────────────────
    private final Paint bgPaint      = new Paint();
    private final Paint gridPaint    = new Paint();
    private final Paint gravPaint    = new Paint();   // gravity reference line
    private final Paint signalPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint livePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path signalPath = new Path();

    // ── Gesture handling ──────────────────────────────────────────────────────
    private final GestureDetector gestureDetector;
    private final OverScroller    scroller;

    private final Runnable flingRunnable = new Runnable() {
        @Override public void run() {
            if (scroller.computeScrollOffset()) {
                viewEndSec = scroller.getCurrX() / 1000f;
                clampViewEnd();
                autoScroll = viewEndSec >= totalSec - 0.3f;
                invalidate();
                postOnAnimation(this);
            }
        }
    };

    // ── Constructors ──────────────────────────────────────────────────────────

    public AccelChartView(Context ctx) {
        this(ctx, null);
    }

    public AccelChartView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);

        float sp10 = sp(ctx, 10);
        float sp12 = sp(ctx, 12);

        bgPaint.setColor(0xFF111111);
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(0xFF2A2A2A);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        gravPaint.setColor(0xFF3A4A3A);
        gravPaint.setStyle(Paint.Style.STROKE);
        gravPaint.setStrokeWidth(1.5f);
        gravPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8, 6}, 0));

        signalPaint.setColor(0xFF00E5CC);
        signalPaint.setStyle(Paint.Style.STROKE);
        signalPaint.setStrokeWidth(2f);
        signalPaint.setStrokeCap(Paint.Cap.ROUND);
        signalPaint.setStrokeJoin(Paint.Join.ROUND);

        labelPaint.setColor(0xFF666666);
        labelPaint.setTextSize(sp10);

        livePaint.setColor(0xFFFF6B35);
        livePaint.setTextSize(sp12);
        livePaint.setFakeBoldText(true);

        scroller = new OverScroller(ctx);
        gestureDetector = new GestureDetector(ctx,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) {
                        scroller.abortAnimation();
                        removeCallbacks(flingRunnable);
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float dx, float dy) {
                        // dx > 0 = finger moves left = going back in time
                        float secsPerPx = WINDOW_SEC / Math.max(getWidth(), 1);
                        viewEndSec += dx * secsPerPx;
                        clampViewEnd();
                        autoScroll = viewEndSec >= totalSec - 0.3f;
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        // vx > 0 = finger flings right = time moves forward
                        float secsPerPx = WINDOW_SEC / Math.max(getWidth(), 1);
                        scroller.fling(
                                (int) (viewEndSec * 1000), 0,
                                (int) (-vx * secsPerPx * 1000), 0,
                                (int) (WINDOW_SEC * 1000),
                                (int) ((totalSec + 1f) * 1000),
                                0, 0);
                        postOnAnimation(flingRunnable);
                        return true;
                    }
                });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Connect the chart to the raw sample list from CadenceDetector.
     * Call once; the chart reads from the list on each refresh.
     */
    public void setData(List<float[]> samples) {
        this.data = samples;
    }

    /**
     * Call periodically (e.g. every 100 ms) to refresh the chart with latest data.
     * This method is fast — it only reads the last element to get total duration,
     * then calls invalidate(). Actual drawing happens in onDraw.
     */
    public void tick() {
        if (data == null) return;
        float latest = 0f;
        synchronized (data) {
            if (!data.isEmpty()) latest = data.get(data.size() - 1)[0];
        }
        totalSec = latest;
        if (autoScroll) viewEndSec = totalSec;
        updateYRange();
        invalidate();
    }

    /** Snap to the latest data and re-enable auto-scroll. */
    public void scrollToEnd() {
        scroller.abortAnimation();
        removeCallbacks(flingRunnable);
        autoScroll   = true;
        viewEndSec   = totalSec;
        invalidate();
    }

    public boolean isAutoScroll() { return autoScroll; }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestureDetector.onTouchEvent(e);
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();

        // Background
        canvas.drawRect(0, 0, w, h, bgPaint);

        if (data == null || w == 0 || h == 0) return;

        final float startSec = viewEndSec - WINDOW_SEC;
        final float yRange   = Math.max(yHi - yLo, 0.1f);

        // ── Vertical grid lines (every 1 s) ──────────────────────────────────
        float gridStart = (float) Math.ceil(startSec / GRID_STEP) * GRID_STEP;
        for (float t = gridStart; t <= viewEndSec; t += GRID_STEP) {
            float x = tToX(t, startSec, w);
            canvas.drawLine(x, 0, x, h, gridPaint);

            // Time label: show mm:ss at every even second
            int tInt = (int) t;
            if (tInt % 2 == 0) {
                String label = String.format(Locale.US, "%d:%02d", tInt / 60, tInt % 60);
                canvas.drawText(label, x + 3, h - 6, labelPaint);
            }
        }

        // ── Gravity reference line ────────────────────────────────────────────
        float yGrav = magToY(GRAVITY, h, yLo, yRange);
        if (yGrav >= 0 && yGrav <= h) {
            canvas.drawLine(0, yGrav, w, yGrav, gravPaint);
        }

        // ── Signal path ───────────────────────────────────────────────────────
        signalPath.reset();
        boolean first   = true;
        int     startIdx = findStartIndex(startSec - 0.1f);

        synchronized (data) {
            int size = data.size();
            for (int i = startIdx; i < size; i++) {
                float[] s = data.get(i);
                float t = s[0];
                if (t > viewEndSec + 0.1f) break;
                float x = tToX(t, startSec, w);
                float y = magToY(s[1], h, yLo, yRange);
                if (first) { signalPath.moveTo(x, y); first = false; }
                else        signalPath.lineTo(x, y);
            }
        }
        canvas.drawPath(signalPath, signalPaint);

        // ── Y-axis labels ─────────────────────────────────────────────────────
        float midY = (yLo + yHi) / 2f;
        canvas.drawText(String.format(Locale.US, "%.1f", yHi), 4, labelPaint.getTextSize() + 4, labelPaint);
        canvas.drawText(String.format(Locale.US, "%.1f", midY), 4, h / 2f, labelPaint);
        canvas.drawText(String.format(Locale.US, "%.1f", yLo), 4, h - 6, labelPaint);

        // ── LIVE indicator ────────────────────────────────────────────────────
        if (autoScroll) {
            String liveStr = "● LIVE";
            float liveW = livePaint.measureText(liveStr);
            canvas.drawText(liveStr, w - liveW - 8, livePaint.getTextSize() + 4, livePaint);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private float tToX(float t, float startSec, int w) {
        return (t - startSec) / WINDOW_SEC * w;
    }

    private float magToY(float mag, int h, float yLo, float yRange) {
        return h * (1f - (mag - yLo) / yRange);
    }

    private void clampViewEnd() {
        float min = WINDOW_SEC;
        float max = totalSec + 0.5f;
        if (viewEndSec < min) viewEndSec = min;
        if (viewEndSec > max) viewEndSec = max;
    }

    /**
     * Binary search for the index of the first sample with time >= targetSec.
     * Requires that rawSamples is sorted by time (it always is).
     */
    private int findStartIndex(float targetSec) {
        if (data == null) return 0;
        synchronized (data) {
            int lo = 0, hi = data.size() - 1, result = data.size();
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (data.get(mid)[0] < targetSec) lo = mid + 1;
                else { result = mid; hi = mid - 1; }
            }
            return result;
        }
    }

    /** Smooth Y range toward visible data min/max. */
    private void updateYRange() {
        if (data == null) return;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float startSec = viewEndSec - WINDOW_SEC;
        int startIdx = findStartIndex(startSec);
        synchronized (data) {
            int size = data.size();
            for (int i = startIdx; i < size; i++) {
                float[] s = data.get(i);
                if (s[0] > viewEndSec) break;
                if (s[1] < lo) lo = s[1];
                if (s[1] > hi) hi = s[1];
            }
        }
        if (lo == Float.MAX_VALUE) return;
        float pad = Math.max((hi - lo) * 0.25f, 1f);
        yLoTgt = lo - pad;
        yHiTgt = hi + pad;
        // Lerp toward target (smooths out sudden amplitude changes)
        yLo = lerp(yLo, yLoTgt, 0.15f);
        yHi = lerp(yHi, yHiTgt, 0.15f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                ctx.getResources().getDisplayMetrics());
    }
}
