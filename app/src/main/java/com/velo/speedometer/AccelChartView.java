package com.velo.speedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.List;
import java.util.Locale;

/**
 * Scrollable + pinch-zoomable chart of raw accelerometer magnitude
 * with an overlaid cadence trace.
 *
 * Y-left axis  : |accel| in m/s²  (cyan line)
 * Y-right axis : cadence in RPM   (thick yellow = stable, thick dashed = uncertain)
 *
 * Touch gestures:
 *   Horizontal drag / fling — scroll in time
 *   Pinch / spread          — zoom window width (1 s … 120 s)
 */
public class AccelChartView extends View {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final float WIN_MIN_SEC  = 1f;
    private static final float WIN_MAX_SEC  = 120f;
    private static final float WIN_DEF_SEC  = 10f;
    private static final float GRID_STEP    = 1f;   // seconds between vertical grid lines
    private static final float GRAVITY      = 9.81f;

    // Cadence Y-axis range shown on the right
    private static final float RPM_MIN_DISPLAY = 40f;
    private static final float RPM_MAX_DISPLAY = 150f;

    // Right-side margin reserved for RPM axis labels
    private static final float RIGHT_PAD_DP = 36f;

    // ── Data ──────────────────────────────────────────────────────────────────
    private List<float[]> accelData   = null;  // [elapsed_sec, magnitude_ms2]
    private List<float[]> cadenceData = null;  // [elapsed_sec, rpm, stable(1/0)]

    private float totalSec   = 0f;
    private float viewEndSec = WIN_DEF_SEC;
    private float windowSec  = WIN_DEF_SEC;  // current zoom level
    private boolean autoScroll = true;

    // ── Y-range for accel (auto-ranging with lerp) ────────────────────────────
    private float yLo = 7f, yHi = 13f;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint bgPaint        = new Paint();
    private final Paint gridPaint      = new Paint();
    private final Paint gravPaint      = new Paint();
    private final Paint accelPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cadenceStable  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cadenceUncert  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelRpmPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint livePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path accelPath   = new Path();
    private final Path stablePath  = new Path();
    private final Path uncertPath  = new Path();

    // ── Gesture ───────────────────────────────────────────────────────────────
    private final GestureDetector      gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller         scroller;

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

    private float rightPadPx;

    // ── Constructors ──────────────────────────────────────────────────────────

    public AccelChartView(Context ctx) { this(ctx, null); }

    public AccelChartView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);

        rightPadPx = dp(ctx, RIGHT_PAD_DP);

        // Background
        bgPaint.setColor(0xFF111111);
        bgPaint.setStyle(Paint.Style.FILL);

        // Vertical grid
        gridPaint.setColor(0xFF252525);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        // Gravity reference (horizontal dashed)
        gravPaint.setColor(0xFF2E4A2E);
        gravPaint.setStyle(Paint.Style.STROKE);
        gravPaint.setStrokeWidth(1.5f);
        gravPaint.setPathEffect(new DashPathEffect(new float[]{8, 6}, 0));

        // Accel signal
        accelPaint.setColor(0xFF00E5CC);
        accelPaint.setStyle(Paint.Style.STROKE);
        accelPaint.setStrokeWidth(1.8f);
        accelPaint.setStrokeCap(Paint.Cap.ROUND);
        accelPaint.setStrokeJoin(Paint.Join.ROUND);

        // Cadence — stable: thick solid yellow
        cadenceStable.setColor(0xFFFFD600);
        cadenceStable.setStyle(Paint.Style.STROKE);
        cadenceStable.setStrokeWidth(3.5f);
        cadenceStable.setStrokeCap(Paint.Cap.ROUND);
        cadenceStable.setStrokeJoin(Paint.Join.ROUND);

        // Cadence — uncertain: thick dashed orange
        cadenceUncert.setColor(0xFFFF8C00);
        cadenceUncert.setStyle(Paint.Style.STROKE);
        cadenceUncert.setStrokeWidth(3f);
        cadenceUncert.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));
        cadenceUncert.setStrokeCap(Paint.Cap.ROUND);

        // Left axis labels (accel)
        labelPaint.setColor(0xFF555555);
        labelPaint.setTextSize(sp(ctx, 10));

        // Right axis labels (RPM)
        labelRpmPaint.setColor(0xFFAA9900);
        labelRpmPaint.setTextSize(sp(ctx, 10));

        // LIVE indicator
        livePaint.setColor(0xFFFF6B35);
        livePaint.setTextSize(sp(ctx, 12));
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
                        if (scaleDetector.isInProgress()) return false;
                        float secsPerPx = windowSec / Math.max(getWidth() - rightPadPx, 1f);
                        viewEndSec += dx * secsPerPx;
                        clampViewEnd();
                        autoScroll = viewEndSec >= totalSec - 0.3f;
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        if (scaleDetector.isInProgress()) return false;
                        float secsPerPx = windowSec / Math.max(getWidth() - rightPadPx, 1f);
                        scroller.fling(
                                (int)(viewEndSec * 1000), 0,
                                (int)(-vx * secsPerPx * 1000), 0,
                                (int)(windowSec * 1000),
                                (int)((totalSec + 1f) * 1000),
                                0, 0);
                        postOnAnimation(flingRunnable);
                        return true;
                    }
                });

        scaleDetector = new ScaleGestureDetector(ctx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    // Focus X in time coordinates at the moment scale begins
                    private float focusSec = 0f;

                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        scroller.abortAnimation();
                        removeCallbacks(flingRunnable);
                        float chartW = Math.max(getWidth() - rightPadPx, 1f);
                        float startSec = viewEndSec - windowSec;
                        focusSec = startSec + (d.getFocusX() / chartW) * windowSec;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float factor = d.getScaleFactor(); // >1 = spread = zoom in
                        float newWindow = windowSec / factor;
                        newWindow = Math.max(WIN_MIN_SEC, Math.min(WIN_MAX_SEC, newWindow));

                        // Keep the focus point stationary on screen while zooming
                        float chartW = Math.max(getWidth() - rightPadPx, 1f);
                        // focusSec was at fraction fx of the old window
                        float fx = (focusSec - (viewEndSec - windowSec)) / windowSec;
                        // After zoom, viewEndSec is adjusted so focusSec stays at fx
                        viewEndSec = focusSec + (1f - fx) * newWindow;
                        windowSec  = newWindow;

                        clampViewEnd();
                        autoScroll = viewEndSec >= totalSec - 0.3f;
                        invalidate();
                        return true;
                    }
                });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setData(List<float[]> accel) {
        this.accelData = accel;
    }

    public void setCadenceData(List<float[]> cadence) {
        this.cadenceData = cadence;
    }

    public List<float[]> getDataSource() { return accelData; }

    public void tick() {
        if (accelData == null) return;
        float latest = 0f;
        synchronized (accelData) {
            if (!accelData.isEmpty()) latest = accelData.get(accelData.size() - 1)[0];
        }
        totalSec = latest;
        if (autoScroll) viewEndSec = totalSec;
        updateYRange();
        invalidate();
    }

    public void scrollToEnd() {
        scroller.abortAnimation();
        removeCallbacks(flingRunnable);
        autoScroll = true;
        viewEndSec = totalSec;
        invalidate();
    }

    public boolean isAutoScroll() { return autoScroll; }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        final int w  = getWidth();
        final int h  = getHeight();
        final float chartW = w - rightPadPx;  // drawable area excluding right RPM axis

        canvas.drawRect(0, 0, w, h, bgPaint);
        if (accelData == null || w == 0 || h == 0) return;

        final float startSec = viewEndSec - windowSec;
        final float yRange   = Math.max(yHi - yLo, 0.1f);

        // ── Vertical time grid ────────────────────────────────────────────────
        float gridStart = (float) Math.ceil(startSec / GRID_STEP) * GRID_STEP;
        for (float t = gridStart; t <= viewEndSec; t += GRID_STEP) {
            float x = tToX(t, startSec, chartW);
            canvas.drawLine(x, 0, x, h, gridPaint);
            int tInt = (int) t;
            if (tInt % 2 == 0) {
                String label = String.format(Locale.US, "%d:%02d", tInt / 60, tInt % 60);
                canvas.drawText(label, x + 3, h - 6, labelPaint);
            }
        }

        // ── Gravity reference (horizontal dashed line on left Y axis) ─────────
        float yGrav = magToY(GRAVITY, h, yLo, yRange);
        if (yGrav >= 0 && yGrav <= h)
            canvas.drawLine(0, yGrav, chartW, yGrav, gravPaint);

        // ── Accel signal ──────────────────────────────────────────────────────
        accelPath.reset();
        boolean first = true;
        int startIdx = findStartIndex(accelData, startSec - 0.1f);
        synchronized (accelData) {
            int size = accelData.size();
            for (int i = startIdx; i < size; i++) {
                float[] s = accelData.get(i);
                if (s[0] > viewEndSec + 0.1f) break;
                float x = tToX(s[0], startSec, chartW);
                float y = magToY(s[1], h, yLo, yRange);
                if (first) { accelPath.moveTo(x, y); first = false; }
                else        accelPath.lineTo(x, y);
            }
        }
        canvas.drawPath(accelPath, accelPaint);

        // ── Cadence overlay ───────────────────────────────────────────────────
        if (cadenceData != null) {
            stablePath.reset();
            uncertPath.reset();
            boolean firstS = true, firstU = true;

            int cStartIdx = findStartIndex(cadenceData, startSec - 2f);
            synchronized (cadenceData) {
                int size = cadenceData.size();
                for (int i = cStartIdx; i < size; i++) {
                    float[] c = cadenceData.get(i);
                    if (c[0] > viewEndSec + 2f) break;

                    float x   = tToX(c[0], startSec, chartW);
                    float rpm = c[1];
                    float y   = rpmToY(rpm, h);
                    boolean stable = c[2] > 0.5f;

                    if (stable) {
                        if (firstS) { stablePath.moveTo(x, y); firstS = false; }
                        else         stablePath.lineTo(x, y);
                        // Break uncertain path so it doesn't connect to stable
                        firstU = true;
                    } else {
                        if (firstU) { uncertPath.moveTo(x, y); firstU = false; }
                        else         uncertPath.lineTo(x, y);
                        // Break stable path
                        firstS = true;
                    }
                }
            }
            canvas.drawPath(stablePath, cadenceStable);
            canvas.drawPath(uncertPath, cadenceUncert);
        }

        // ── Left Y-axis labels (m/s²) ─────────────────────────────────────────
        float textH = labelPaint.getTextSize();
        canvas.drawText(String.format(Locale.US, "%.1f", yHi), 3, textH + 2, labelPaint);
        canvas.drawText(String.format(Locale.US, "%.1f", (yLo + yHi) / 2), 3, h / 2f, labelPaint);
        canvas.drawText(String.format(Locale.US, "%.1f", yLo), 3, h - 4, labelPaint);

        // ── Right Y-axis labels (RPM) ─────────────────────────────────────────
        float rTextH = labelRpmPaint.getTextSize();
        String rTop  = Math.round(RPM_MAX_DISPLAY) + "";
        String rMid  = Math.round((RPM_MIN_DISPLAY + RPM_MAX_DISPLAY) / 2) + "";
        String rBot  = Math.round(RPM_MIN_DISPLAY) + "";
        float lw = labelRpmPaint.measureText(rTop);
        canvas.drawText(rTop, w - lw - 2, rTextH + 2,       labelRpmPaint);
        canvas.drawText(rMid, w - labelRpmPaint.measureText(rMid) - 2, h / 2f, labelRpmPaint);
        canvas.drawText(rBot, w - labelRpmPaint.measureText(rBot) - 2, h - 4,  labelRpmPaint);

        // ── Zoom hint (when not at default zoom) ──────────────────────────────
        if (Math.abs(windowSec - WIN_DEF_SEC) > 0.5f) {
            String zoomStr = String.format(Locale.US, "%.0f s", windowSec);
            canvas.drawText(zoomStr, chartW / 2 - labelPaint.measureText(zoomStr) / 2,
                    rTextH + 2, labelPaint);
        }

        // ── LIVE indicator ────────────────────────────────────────────────────
        if (autoScroll) {
            String s = "● LIVE";
            canvas.drawText(s, chartW - livePaint.measureText(s) - 4,
                    livePaint.getTextSize() + 4, livePaint);
        }
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    /** Time → X pixel within [0, chartW]. */
    private float tToX(float t, float startSec, float chartW) {
        return (t - startSec) / windowSec * chartW;
    }

    /** Accel magnitude → Y pixel. */
    private float magToY(float mag, int h, float yLo, float yRange) {
        return h * (1f - (mag - yLo) / yRange);
    }

    /** RPM → Y pixel on the right axis. */
    private float rpmToY(float rpm, int h) {
        float frac = (rpm - RPM_MIN_DISPLAY) / (RPM_MAX_DISPLAY - RPM_MIN_DISPLAY);
        return h * (1f - frac);
    }

    private void clampViewEnd() {
        float min = windowSec;
        float max = totalSec + 0.5f;
        if (viewEndSec < min) viewEndSec = min;
        if (viewEndSec > max) viewEndSec = max;
    }

    /** Binary search: first index where data[i][0] >= targetSec. */
    private int findStartIndex(List<float[]> list, float targetSec) {
        if (list == null) return 0;
        synchronized (list) {
            int lo = 0, hi = list.size() - 1, result = list.size();
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (list.get(mid)[0] < targetSec) lo = mid + 1;
                else { result = mid; hi = mid - 1; }
            }
            return result;
        }
    }

    private void updateYRange() {
        if (accelData == null) return;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float startSec = viewEndSec - windowSec;
        int idx = findStartIndex(accelData, startSec);
        synchronized (accelData) {
            int size = accelData.size();
            for (int i = idx; i < size; i++) {
                float[] s = accelData.get(i);
                if (s[0] > viewEndSec) break;
                if (s[1] < lo) lo = s[1];
                if (s[1] > hi) hi = s[1];
            }
        }
        if (lo == Float.MAX_VALUE) return;
        float pad = Math.max((hi - lo) * 0.25f, 1f);
        float newLo = lo - pad, newHi = hi + pad;
        yLo = lerp(yLo, newLo, 0.15f);
        yHi = lerp(yHi, newHi, 0.15f);
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                ctx.getResources().getDisplayMetrics());
    }

    private static float dp(Context ctx, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}
