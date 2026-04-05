package com.velo.speedometer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates bicycle cadence (RPM) from the phone's accelerometer.
 *
 * Algorithm:
 *   1. Collect |accel| magnitudes into a circular buffer (~10 s).
 *   2. Every STEP_SIZE new samples (~1 s), run a Hann-windowed FFT.
 *   3. Find the dominant peak in 50–140 RPM range.
 *   4. Compare peak power to noise floor (SNR); report 0 if no clear peak.
 *   5. Parabolic interpolation for sub-bin frequency accuracy.
 *
 * Raw samples are also stored as [elapsed_sec, magnitude_ms2] for the
 * graph view and CSV export.  No external libraries — includes Cooley-Tukey FFT.
 */
public class CadenceDetector implements SensorEventListener {

    public interface Listener {
        /** Called on the main thread. rpm == 0 means "not pedalling". */
        void onCadence(float rpm);
    }

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int   SAMPLE_RATE   = 50;      // Hz (SENSOR_DELAY_GAME)
    private static final int   BUFFER_SIZE   = 512;     // ~10.24 s, power of 2
    private static final int   STEP_SIZE     = 50;      // recompute every ~1 s
    private static final float RPM_MIN       = 50f;
    private static final float RPM_MAX       = 140f;
    private static final float SNR_THRESHOLD = 5f;
    /** Max raw samples ≈ 2 h at 50 Hz */
    private static final int   MAX_RAW       = 360_000;

    // ── Internals ─────────────────────────────────────────────────────────────
    private final SensorManager sensorManager;
    private final Listener      listener;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    private final float[] circBuf = new float[BUFFER_SIZE];
    private int head      = 0;
    private int filled    = 0;
    private int stepCount = 0;

    /** History for graph/CSV. Synchronize on this list before iterating. */
    private final List<float[]> rawSamples = new ArrayList<>();
    private long rideStartMs = -1;

    private float lastRpm = 0f;

    // ── Public API ────────────────────────────────────────────────────────────

    public CadenceDetector(Context ctx, Listener listener) {
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
    }

    public void start() {
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null)
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        head        = 0;
        filled      = 0;
        stepCount   = 0;
        lastRpm     = 0f;
        rideStartMs = System.currentTimeMillis();
        synchronized (rawSamples) { rawSamples.clear(); }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        lastRpm = 0f;
    }

    public float getLastRpm() { return lastRpm; }

    /**
     * Raw samples since ride start.  Each entry: float[]{elapsed_sec, magnitude_ms2}.
     * <b>Caller must synchronize on the returned list when iterating.</b>
     */
    public List<float[]> getRawSamples() { return rawSamples; }

    // ── SensorEventListener ───────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        float ax = event.values[0], ay = event.values[1], az = event.values[2];
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);

        // FFT circular buffer
        circBuf[head] = mag;
        head = (head + 1) % BUFFER_SIZE;
        if (filled < BUFFER_SIZE) filled++;
        if (++stepCount >= STEP_SIZE && filled == BUFFER_SIZE) {
            stepCount = 0;
            processBuffer();
        }

        // Raw history for graph / CSV
        if (rideStartMs >= 0) {
            float elapsed = (System.currentTimeMillis() - rideStartMs) / 1000f;
            synchronized (rawSamples) {
                if (rawSamples.size() < MAX_RAW)
                    rawSamples.add(new float[]{elapsed, mag});
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── FFT + cadence detection ───────────────────────────────────────────────

    private void processBuffer() {
        float[] signal = new float[BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++)
            signal[i] = circBuf[(head + i) % BUFFER_SIZE];

        // Remove DC
        float mean = 0f;
        for (float v : signal) mean += v;
        mean /= BUFFER_SIZE;
        for (int i = 0; i < BUFFER_SIZE; i++) signal[i] -= mean;

        // Hann window
        for (int i = 0; i < BUFFER_SIZE; i++)
            signal[i] *= 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (BUFFER_SIZE - 1)));

        float[] power = fft(signal);

        int kMin = (int) Math.ceil (RPM_MIN / 60f * BUFFER_SIZE / SAMPLE_RATE);
        int kMax = (int) Math.floor(RPM_MAX / 60f * BUFFER_SIZE / SAMPLE_RATE);

        int   peakK = kMin;
        float peakPow = 0f;
        for (int k = kMin; k <= kMax; k++) {
            if (power[k] > peakPow) { peakPow = power[k]; peakK = k; }
        }

        float noiseSum = 0f;
        int   noiseCnt = 0;
        for (int k = 1; k < BUFFER_SIZE / 2; k++) {
            if (Math.abs(k - peakK) > 2) { noiseSum += power[k]; noiseCnt++; }
        }
        float noise = noiseCnt > 0 ? noiseSum / noiseCnt : 1f;
        float snr   = peakPow / Math.max(noise, 1e-9f);

        float rpm;
        if (snr < SNR_THRESHOLD) {
            rpm = 0f;
        } else {
            float refined = peakK;
            if (peakK > kMin && peakK < kMax) {
                float y0 = power[peakK - 1], y1 = power[peakK], y2 = power[peakK + 1];
                float denom = 2f * (y0 - 2f * y1 + y2);
                if (Math.abs(denom) > 1e-9f)
                    refined = peakK - (y2 - y0) / denom;
            }
            rpm = refined * SAMPLE_RATE / BUFFER_SIZE * 60f;
            rpm = Math.max(RPM_MIN, Math.min(RPM_MAX, rpm));
        }

        lastRpm = rpm;
        if (listener != null) {
            final float r = rpm;
            mainHandler.post(() -> listener.onCadence(r));
        }
    }

    // ── Cooley-Tukey radix-2 in-place FFT ────────────────────────────────────

    /** Returns power spectrum of length N/2: power[k] = |X[k]|². N must be power of 2. */
    private static float[] fft(float[] input) {
        int N = input.length;
        float[] re = input.clone();
        float[] im = new float[N];

        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; }
        }

        for (int len = 2; len <= N; len <<= 1) {
            float wRe = (float) Math.cos(-2.0 * Math.PI / len);
            float wIm = (float) Math.sin(-2.0 * Math.PI / len);
            for (int i = 0; i < N; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int j = 0; j < len / 2; j++) {
                    float uRe = re[i+j], uIm = im[i+j];
                    float vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    float vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i+j] = uRe+vRe; im[i+j] = uIm+vIm;
                    re[i+j+len/2] = uRe-vRe; im[i+j+len/2] = uIm-vIm;
                    float tmp = curRe*wRe - curIm*wIm;
                    curIm = curRe*wIm + curIm*wRe;
                    curRe = tmp;
                }
            }
        }

        float[] power = new float[N / 2];
        for (int i = 0; i < N / 2; i++)
            power[i] = re[i]*re[i] + im[i]*im[i];
        return power;
    }
}
