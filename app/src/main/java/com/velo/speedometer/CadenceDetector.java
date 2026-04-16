package com.velo.speedometer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Estimates bicycle cadence (RPM) from the phone accelerometer.
 *
 * DSP pipeline (per sensor event):
 *   1. Per-axis first-order IIR low-pass filter (cutoff ≈ 6 Hz).
 *      Eliminates road/frame vibration (≥ 8–10 Hz) before any non-linear step.
 *   2. Circular buffer per axis (X, Y, Z). No sqrt — stays linear.
 *
 * DSP pipeline (per FFT window, every ~1 s):
 *   3. DC removal per axis.
 *   4. Hann window per axis.
 *   5. FFT per axis → power spectrum.
 *   6. Select the TWO axes with the strongest peak in the cadence band.
 *      The weakest axis is discarded — it is most likely noise-dominated.
 *   7. Combined spectrum = geometric mean of the two selected power spectra:
 *        combined[k] = sqrt(pwA[k] * pwB[k])  =  |X_A[k]| * |X_B[k]|
 *      This equals |X_A[k] · conj(X_B[k])| — the cross-spectrum magnitude.
 *      Independent noise peaks are attenuated because they are large in at
 *      most one axis; a coherent cadence signal is large in both.
 *
 * Stability is determined by THREE independent criteria — all must pass:
 *   1. SNR  — peak power vs. noise floor of the combined spectrum.
 *   2. Harmonic consistency — presence of the 2nd or 3rd harmonic.
 *   3. Temporal stability — low variance of the last N estimates.
 *
 * Graph samples are the LOW-PASS FILTERED magnitude sqrt(lpfX²+lpfY²+lpfZ²)
 * — the same signal the FFT sees, so the graph is consistent with detection.
 */
public class CadenceDetector implements SensorEventListener {

    // ── Result ────────────────────────────────────────────────────────────────

    public static class Result {
        public final float   rpm;
        public final float   confidence;
        public final boolean stable;
        public final float   stableAvgRpm;

        Result(float rpm, float confidence, boolean stable, float stableAvgRpm) {
            this.rpm          = rpm;
            this.confidence   = confidence;
            this.stable       = stable;
            this.stableAvgRpm = stableAvgRpm;
        }

        public static final Result EMPTY = new Result(0, 0, false, 0);
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface Listener {
        void onCadence(Result result);
    }

    // ── Tuning constants ──────────────────────────────────────────────────────

    private static final int   SAMPLE_RATE      = 50;
    private static final int   BUFFER_SIZE      = 512;   // ~10.24 s, power of 2
    private static final int   STEP_SIZE        = 50;    // recompute every ~1 s
    private static final float RPM_MIN          = 48f;
    private static final float RPM_MAX          = 108f;

    private static final float SNR_DETECT       = 4f;
    private static final float SNR_STABLE       = 12f;
    private static final float HARMONIC_MIN_SNR = 3f;
    private static final float TEMPORAL_MAX_STD = 4f;    // RPM
    private static final int   TEMPORAL_N       = 8;
    private static final int   STABLE_AVG_SEC   = 30;

    /**
     * IIR LPF memory coefficient: α = exp(−2π·6/50) ≈ 0.470
     * Cutoff ≈ 6 Hz — passes cadence (0.75–1.67 Hz) and its 3rd harmonic
     * (≤ 5 Hz); strongly attenuates road/frame vibration above ~8 Hz.
     */
    private static final float LPF_COEFF = 0.470f;

    private static final int MAX_RAW = 360_000;   // ~2 h at 50 Hz

    // ── State ─────────────────────────────────────────────────────────────────

    private final SensorManager sensorManager;
    private final Listener      listener;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    // Per-axis circular buffers (filtered)
    private final float[] circX = new float[BUFFER_SIZE];
    private final float[] circY = new float[BUFFER_SIZE];
    private final float[] circZ = new float[BUFFER_SIZE];
    private int head      = 0;
    private int filled    = 0;
    private int stepCount = 0;

    // IIR filter state
    private float lpfX = 0f, lpfY = 0f, lpfZ = 0f;

    // Temporal stability
    private final Deque<Float> recentRpm = new ArrayDeque<>(TEMPORAL_N + 1);

    // Rolling average of stable readings only
    private final Deque<long[]> stableHistory = new ArrayDeque<>();  // [ms, rpm*100]

    // Graph / CSV data
    private final List<float[]> rawSamples     = new ArrayList<>();  // [elapsed_s, filteredMag]
    private final List<float[]> cadenceHistory = new ArrayList<>();  // [elapsed_s, rpm, stable]
    private long rideStartMs = -1;

    private Result lastResult = Result.EMPTY;

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
        lpfX        = 0f;
        lpfY        = 0f;
        lpfZ        = 0f;
        rideStartMs = System.currentTimeMillis();
        recentRpm.clear();
        synchronized (stableHistory)  { stableHistory.clear(); }
        synchronized (rawSamples)     { rawSamples.clear(); }
        synchronized (cadenceHistory) { cadenceHistory.clear(); }
        lastResult = Result.EMPTY;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        lastResult = Result.EMPTY;
    }

    public Result getLastResult() { return lastResult; }

    /** Synchronize on the returned list before iterating. */
    public List<float[]> getRawSamples()     { return rawSamples; }
    public List<float[]> getCadenceHistory() { return cadenceHistory; }

    // ── SensorEventListener ───────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        float ax = event.values[0], ay = event.values[1], az = event.values[2];

        // Per-axis IIR low-pass (fc ≈ 6 Hz)
        lpfX = LPF_COEFF * lpfX + (1f - LPF_COEFF) * ax;
        lpfY = LPF_COEFF * lpfY + (1f - LPF_COEFF) * ay;
        lpfZ = LPF_COEFF * lpfZ + (1f - LPF_COEFF) * az;

        circX[head] = lpfX;
        circY[head] = lpfY;
        circZ[head] = lpfZ;
        head = (head + 1) % BUFFER_SIZE;
        if (filled < BUFFER_SIZE) filled++;

        if (++stepCount >= STEP_SIZE && filled == BUFFER_SIZE) {
            stepCount = 0;
            processBuffer();
        }

        // Graph: filtered magnitude — exactly what the FFT sees
        if (rideStartMs >= 0) {
            float filtMag = (float) Math.sqrt(lpfX * lpfX + lpfY * lpfY + lpfZ * lpfZ);
            float elapsed = (System.currentTimeMillis() - rideStartMs) / 1000f;
            synchronized (rawSamples) {
                if (rawSamples.size() < MAX_RAW)
                    rawSamples.add(new float[]{elapsed, filtMag});
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── DSP core ─────────────────────────────────────────────────────────────

    private void processBuffer() {

        float[] sigX = unroll(circX);
        float[] sigY = unroll(circY);
        float[] sigZ = unroll(circZ);

        removeDC(sigX); removeDC(sigY); removeDC(sigZ);
        applyHann(sigX); applyHann(sigY); applyHann(sigZ);

        float[] pwX = fft(sigX);
        float[] pwY = fft(sigY);
        float[] pwZ = fft(sigZ);

        // Cadence bin range
        int kMin = (int) Math.ceil (RPM_MIN / 60f * BUFFER_SIZE / SAMPLE_RATE);
        int kMax = (int) Math.floor(RPM_MAX / 60f * BUFFER_SIZE / SAMPLE_RATE);

        // Peak power of each axis within the cadence band
        float peakX = bandPeak(pwX, kMin, kMax);
        float peakY = bandPeak(pwY, kMin, kMax);
        float peakZ = bandPeak(pwZ, kMin, kMax);

        // Discard the weakest axis; correlate the two strongest.
        // The weakest axis carries the least cadence energy — including it
        // in a geometric mean would only suppress the result without adding
        // useful signal.
        float[] pwA, pwB;
        if (peakX <= peakY && peakX <= peakZ) {
            pwA = pwY; pwB = pwZ;   // X is weakest — drop it
        } else if (peakY <= peakX && peakY <= peakZ) {
            pwA = pwX; pwB = pwZ;   // Y is weakest — drop it
        } else {
            pwA = pwX; pwB = pwY;   // Z is weakest — drop it
        }

        // Combined spectrum = geometric mean of the two selected power spectra:
        //   combined[k] = sqrt(|X_A[k]|² · |X_B[k]|²)  =  |X_A[k]| · |X_B[k]|
        // This equals |X_A[k] · conj(X_B[k])| — the cross-spectrum magnitude.
        // A noise peak present in only one axis is multiplied by the (small)
        // value in the other axis, so it is attenuated in the combined result.
        // A cadence peak coherent in both axes survives at full strength.
        float[] combined = new float[BUFFER_SIZE / 2];
        for (int k = 0; k < combined.length; k++)
            combined[k] = (float) Math.sqrt(pwA[k] * pwB[k]);

        // Find peak in cadence band
        int   peakK   = kMin;
        float peakPow = 0f;
        for (int k = kMin; k <= kMax; k++) {
            if (combined[k] > peakPow) { peakPow = combined[k]; peakK = k; }
        }

        // Noise floor: mean combined power outside ±2 bins of the peak
        float noiseSum = 0f;
        int   noiseCnt = 0;
        for (int k = 1; k < BUFFER_SIZE / 2; k++) {
            if (Math.abs(k - peakK) > 2) { noiseSum += combined[k]; noiseCnt++; }
        }
        float noise = noiseCnt > 0 ? noiseSum / noiseCnt : 1f;

        // ── CRITERION 1: SNR ──────────────────────────────────────────────────
        float snr = peakPow / Math.max(noise, 1e-9f);
        if (snr < SNR_DETECT) {
            publish(0f, 0f, false);
            return;
        }

        // Sub-bin accuracy via parabolic interpolation
        float refined = peakK;
        if (peakK > kMin && peakK < kMax) {
            float y0 = combined[peakK - 1], y1 = combined[peakK], y2 = combined[peakK + 1];
            float denom = 2f * (y0 - 2f * y1 + y2);
            if (Math.abs(denom) > 1e-9f)
                refined = peakK - (y2 - y0) / denom;
        }
        float rpm = refined * SAMPLE_RATE / BUFFER_SIZE * 60f;
        rpm = Math.max(RPM_MIN, Math.min(RPM_MAX, rpm));

        boolean snrPass = snr >= SNR_STABLE;

        // ── CRITERION 2: Harmonic consistency ────────────────────────────────
        boolean harmonicPass = false;
        int k2 = Math.round(refined * 2);
        int k3 = Math.round(refined * 3);
        if (k2 < BUFFER_SIZE / 2 && combined[k2] > noise * HARMONIC_MIN_SNR) harmonicPass = true;
        if (k3 < BUFFER_SIZE / 2 && combined[k3] > noise * HARMONIC_MIN_SNR) harmonicPass = true;

        // ── CRITERION 3: Temporal stability ──────────────────────────────────
        recentRpm.addLast(rpm);
        if (recentRpm.size() > TEMPORAL_N) recentRpm.pollFirst();
        boolean temporalPass = false;
        if (recentRpm.size() >= TEMPORAL_N) {
            float sumR = 0f;
            for (float r : recentRpm) sumR += r;
            float meanR = sumR / recentRpm.size();
            float varR  = 0f;
            for (float r : recentRpm) varR += (r - meanR) * (r - meanR);
            temporalPass = (float) Math.sqrt(varR / recentRpm.size()) <= TEMPORAL_MAX_STD;
        }

        // Confidence score: geometric mean of three sub-scores in [0,1]
        float snrScore      = Math.min(1f, (snr - SNR_DETECT) / (SNR_STABLE - SNR_DETECT));
        float harmonicScore = harmonicPass ? 1.0f : 0.3f;
        float temporalScore = recentRpm.size() < TEMPORAL_N ? 0.5f : (temporalPass ? 1.0f : 0.2f);
        float confidence    = (float) Math.pow(snrScore * harmonicScore * temporalScore, 1.0 / 3.0);

        publish(rpm, confidence, snrPass && harmonicPass && temporalPass);
    }

    // ── DSP helpers ───────────────────────────────────────────────────────────

    /** Maximum power in [kMin, kMax] of a power spectrum. */
    private static float bandPeak(float[] pw, int kMin, int kMax) {
        float max = 0f;
        for (int k = kMin; k <= kMax; k++) if (pw[k] > max) max = pw[k];
        return max;
    }

    /** Unroll circular buffer into contiguous array starting from oldest sample. */
    private float[] unroll(float[] circ) {
        float[] out = new float[BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++)
            out[i] = circ[(head + i) % BUFFER_SIZE];
        return out;
    }

    /** In-place DC removal (subtract mean). */
    private static void removeDC(float[] sig) {
        float mean = 0f;
        for (float v : sig) mean += v;
        mean /= sig.length;
        for (int i = 0; i < sig.length; i++) sig[i] -= mean;
    }

    /** In-place Hann window. */
    private static void applyHann(float[] sig) {
        int N = sig.length;
        for (int i = 0; i < N; i++)
            sig[i] *= 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (N - 1)));
    }

    // ── Publish result ────────────────────────────────────────────────────────

    private void publish(float rpm, float confidence, boolean stable) {
        long now      = System.currentTimeMillis();
        long cutoffMs = now - (long) STABLE_AVG_SEC * 1000L;

        if (stable && rpm > 0)
            synchronized (stableHistory) {
                stableHistory.addLast(new long[]{now, (long)(rpm * 100)});
            }

        float stableAvg = 0f;
        synchronized (stableHistory) {
            while (!stableHistory.isEmpty() && stableHistory.peekFirst()[0] < cutoffMs)
                stableHistory.pollFirst();
            if (!stableHistory.isEmpty()) {
                double sum = 0;
                for (long[] e : stableHistory) sum += e[1];
                stableAvg = (float)(sum / stableHistory.size() / 100.0);
            }
        }

        lastResult = new Result(rpm, confidence, stable, stableAvg);

        if (rideStartMs >= 0 && rpm > 0) {
            float elapsed = (now - rideStartMs) / 1000f;
            synchronized (cadenceHistory) {
                cadenceHistory.add(new float[]{elapsed, rpm, stable ? 1f : 0f});
            }
        }

        if (listener != null) {
            final Result r = lastResult;
            mainHandler.post(() -> listener.onCadence(r));
        }
    }

    // ── Cooley-Tukey radix-2 FFT (N must be power of 2) ──────────────────────

    /** Returns power spectrum of length N/2: power[k] = |X[k]|². */
    private static float[] fft(float[] input) {
        int     N  = input.length;
        float[] re = input.clone();
        float[] im = new float[N];

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; }
        }

        // Butterfly stages
        for (int len = 2; len <= N; len <<= 1) {
            float wRe = (float) Math.cos(-2.0 * Math.PI / len);
            float wIm = (float) Math.sin(-2.0 * Math.PI / len);
            for (int i = 0; i < N; i += len) {
                float cRe = 1f, cIm = 0f;
                for (int j = 0; j < len / 2; j++) {
                    float uRe = re[i+j];
                    float uIm = im[i+j];
                    float vRe = re[i+j+len/2] * cRe - im[i+j+len/2] * cIm;
                    float vIm = re[i+j+len/2] * cIm + im[i+j+len/2] * cRe;
                    re[i+j]       = uRe + vRe;  im[i+j]       = uIm + vIm;
                    re[i+j+len/2] = uRe - vRe;  im[i+j+len/2] = uIm - vIm;
                    float tmp = cRe * wRe - cIm * wIm;
                    cIm = cRe * wIm + cIm * wRe;
                    cRe = tmp;
                }
            }
        }

        float[] power = new float[N / 2];
        for (int i = 0; i < N / 2; i++) power[i] = re[i]*re[i] + im[i]*im[i];
        return power;
    }
}
