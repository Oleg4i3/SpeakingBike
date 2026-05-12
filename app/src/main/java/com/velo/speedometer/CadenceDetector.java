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
 * Cadence detector — configurable sensor × method (4 combinations).
 *
 * ── Sensor choice ─────────────────────────────────────────────────────────────
 *
 * GYROSCOPE (useGyro = true):
 *   Measures angular velocity (rad/s). Road impacts create LINEAR acceleration
 *   but almost no angular velocity → gyroscope is inherently cleaner on rough
 *   terrain.
 *
 *   IMPORTANT — sign matters, magnitude does not:
 *   The leg sweeps up (+ω) then down (−ω). Taking sqrt(x²+y²+z²) destroys the
 *   sign and makes both half-cycles look identical → the signal has frequency
 *   2× cadence, and the detector finds the 2nd harmonic instead of the fundamental.
 *
 *   Fix: each window, pick the axis with highest variance (= dominant rotation
 *   axis, whichever way the phone sits in the pocket) and use its SIGNED values.
 *   The signal then alternates +ω / −ω at the true cadence frequency.
 *
 * ACCELEROMETER (useGyro = false):
 *   Measures linear acceleration including gravity (m/s²). Gravity on the
 *   vertical axis creates a natural asymmetry: leg up → (g + a_pedal),
 *   leg down → (g − a_pedal). Magnitude sqrt(x²+y²+z²) preserves this
 *   asymmetry and the fundamental cadence frequency is correct.
 *   More sensitive to road impacts than gyroscope.
 *
 * ── Method choice ────────────────────────────────────────────────────────────
 *
 * ACF (useAcf = true)  — autocorrelation:
 *   R[lag] = Σ signal[n]·signal[n+lag] / energy
 *   Measures periodicity directly. A single road bump contributes only at
 *   lag = 0, not at the cadence lag → better bump rejection.
 *   Fundamental always produces the strongest ACF peak, never a harmonic.
 *
 * SPECTRAL (useAcf = false) — FFT power spectrum:
 *   Classic frequency-domain approach. Faster conceptually but more
 *   susceptible to harmonic confusion and noise floors in rough terrain.
 *   Sub-harmonic guard included to correct the most common 2× error.
 *
 * ── Placement ────────────────────────────────────────────────────────────────
 *   Trouser pocket (thigh level). Hip bag / frame bag give much weaker signal.
 */
public class CadenceDetector implements SensorEventListener {

    // ── Result ────────────────────────────────────────────────────────────────

    public static class Result {
        public final float   rpm;
        public final float   confidence;
        public final boolean stable;
        public final float   stableAvgRpm;

        Result(float rpm, float confidence, boolean stable, float stableAvgRpm) {
            this.rpm = rpm; this.confidence = confidence;
            this.stable = stable; this.stableAvgRpm = stableAvgRpm;
        }
        public static final Result EMPTY = new Result(0, 0, false, 0);
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface Listener { void onCadence(Result result); }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final int   SAMPLE_RATE      = 50;
    private static final int   BUFFER_SIZE      = 512;   // ~10.24 s
    private static final int   STEP_SIZE        = 50;    // recompute every ~1 s
    private static final float RPM_MIN          = 48f;
    private static final float RPM_MAX          = 108f;

    // ACF thresholds
    private static final float ACF_DETECT       = 0.15f;
    private static final float ACF_STABLE       = 0.35f;
    private static final float ACF_PERIOD2_MIN  = 0.08f;

    // Spectral thresholds
    private static final float SNR_DETECT       = 4f;
    private static final float SNR_STABLE       = 12f;
    private static final float HARMONIC_MIN_SNR = 3f;
    private static final float SUBHARMONIC_SNR  = 3f;

    // Shared
    private static final float TEMPORAL_MAX_STD = 4f;
    private static final int   TEMPORAL_N       = 8;
    private static final int   STABLE_AVG_SEC   = 30;
    private static final float LPF_COEFF        = 0.470f; // fc ≈ 6 Hz
    private static final int   MAX_RAW          = 360_000;

    // ── State ─────────────────────────────────────────────────────────────────

    private final SensorManager sensorManager;
    private final Listener      listener;
    private final boolean       useGyro;
    private final boolean       useAcf;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    // Three per-axis circular buffers — always filled regardless of mode.
    // Gyro mode picks the dominant axis from these; accel mode uses magnitude.
    private final float[] circX = new float[BUFFER_SIZE];
    private final float[] circY = new float[BUFFER_SIZE];
    private final float[] circZ = new float[BUFFER_SIZE];
    private int head = 0, filled = 0, stepCount = 0;

    // IIR filter state
    private float lpfX = 0f, lpfY = 0f, lpfZ = 0f;

    // ── Adaptive sample-rate measurement ─────────────────────────────────────
    // event.timestamp is nanoseconds from a monotonic clock. We compute the
    // per-sample interval via EMA and derive the true Hz. This corrects the
    // systematic ~15 RPM under-read that appears when the hardware delivers
    // ~60-62 Hz on SENSOR_DELAY_GAME instead of the nominal 50 Hz.
    private static final float SR_EMA_ALPHA   = 0.02f;  // slow: stable after ~150 samples
    private static final float SR_MIN         = 30f;    // sanity guard (Hz)
    private static final float SR_MAX         = 200f;   // sanity guard (Hz)
    private long  lastEventNanos     = 0L;
    private float measuredSampleRate = SAMPLE_RATE;     // Hz, updated on every sample

    // Temporal stability
    private final Deque<Float>  recentRpm     = new ArrayDeque<>(TEMPORAL_N + 1);
    private final Deque<long[]> stableHistory = new ArrayDeque<>();
    private final List<float[]> rawSamples    = new ArrayList<>();
    private final List<float[]> cadenceHistory= new ArrayList<>();

    /**
     * FIX (bugs 1+2): rideStartMs is now set externally by SpeedometerService
     * so sensor timestamps and GPS/HR timestamps use the same time origin.
     * Previously this was set independently in start(), causing a small but
     * visible drift between the top (sensor) and bottom (speed/HR) chart panels,
     * and repeating/misaligned cadence values in the ride CSV.
     */
    private long rideStartMs = -1;

    private Result lastResult = Result.EMPTY;
    private volatile boolean paused = false;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * @param useGyro  true = gyroscope (dominant signed axis),
     *                 false = accelerometer (magnitude)
     * @param useAcf   true = autocorrelation,
     *                 false = FFT power spectrum
     */
    public CadenceDetector(Context ctx, Listener listener,
                           boolean useGyro, boolean useAcf) {
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
        this.useGyro  = useGyro;
        this.useAcf   = useAcf;
    }

    /**
     * Start with an externally-provided ride start timestamp.
     * Call this from SpeedometerService.startTracking() passing service.rideStartMs
     * so both the sensor data and GPS/HR data share the same time origin.
     */
    public void start(long extRideStartMs) {
        int sensorType = useGyro ? Sensor.TYPE_GYROSCOPE : Sensor.TYPE_ACCELEROMETER;
        Sensor sensor  = sensorManager.getDefaultSensor(sensorType);
        if (sensor != null)
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);

        head = 0; filled = 0; stepCount = 0;
        lpfX = 0f; lpfY = 0f; lpfZ = 0f;
        lastEventNanos     = 0L;
        measuredSampleRate = SAMPLE_RATE;
        rideStartMs = extRideStartMs;   // ← use external timestamp (FIX bug 1+2)
        recentRpm.clear();
        synchronized (stableHistory)  { stableHistory.clear(); }
        synchronized (rawSamples)     { rawSamples.clear(); }
        synchronized (cadenceHistory) { cadenceHistory.clear(); }
        lastResult = Result.EMPTY;
        paused = false;   // always start fresh
    }

    /** Convenience overload — uses current wall clock. Prefer start(long) when possible. */
    public void start() {
        start(System.currentTimeMillis());
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        lastResult = Result.EMPTY;
    }

    public Result        getLastResult()    { return lastResult; }
    public List<float[]> getRawSamples()    { return rawSamples; }
    public List<float[]> getCadenceHistory(){ return cadenceHistory; }

    /** When paused, sensor keeps running but no data is written to graph/history. */
    public void setPaused(boolean paused) { this.paused = paused; }

    // ── SensorEventListener ───────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        // ── Measure actual hardware sample rate ───────────────────────────────
        // event.timestamp is nanoseconds (CLOCK_BOOTTIME or CLOCK_MONOTONIC).
        // A single-sample EMA converges to true Hz within a few seconds and
        // removes the systematic RPM bias caused by hardware over-delivering
        // samples (e.g. 60 Hz on a device configured for SENSOR_DELAY_GAME).
        final long ts = event.timestamp;
        if (lastEventNanos > 0L) {
            long dtNs = ts - lastEventNanos;
            // Accept 2 ms … 200 ms (5 Hz … 500 Hz) — anything outside is a glitch
            if (dtNs > 2_000_000L && dtNs < 200_000_000L) {
                float sampleHz = 1_000_000_000f / dtNs;
                measuredSampleRate = SR_EMA_ALPHA * sampleHz
                                   + (1f - SR_EMA_ALPHA) * measuredSampleRate;
                // Hard clamp — never let a single glitch corrupt the estimator
                if (measuredSampleRate < SR_MIN) measuredSampleRate = SR_MIN;
                if (measuredSampleRate > SR_MAX) measuredSampleRate = SR_MAX;
            }
        }
        lastEventNanos = ts;

        float rx = event.values[0], ry = event.values[1], rz = event.values[2];

        // Per-axis IIR LPF (fc ≈ 6 Hz)
        lpfX = LPF_COEFF * lpfX + (1f - LPF_COEFF) * rx;
        lpfY = LPF_COEFF * lpfY + (1f - LPF_COEFF) * ry;
        lpfZ = LPF_COEFF * lpfZ + (1f - LPF_COEFF) * rz;

        // Store all three filtered axes in separate circular buffers.
        // Signal extraction (axis selection vs. magnitude) happens in processBuffer()
        // once per window — not per sample — so we always keep all three.
        circX[head] = lpfX;
        circY[head] = lpfY;
        circZ[head] = lpfZ;
        head = (head + 1) % BUFFER_SIZE;
        if (filled < BUFFER_SIZE) filled++;

        if (++stepCount >= STEP_SIZE && filled == BUFFER_SIZE) {
            stepCount = 0;
            processBuffer();
        }

        // Graph: store the working signal magnitude for visualization (skip during pauses)
        if (rideStartMs >= 0 && !paused) {
            float mag = (float) Math.sqrt(lpfX*lpfX + lpfY*lpfY + lpfZ*lpfZ);
            float elapsed = (System.currentTimeMillis() - rideStartMs) / 1000f;
            synchronized (rawSamples) {
                if (rawSamples.size() < MAX_RAW)
                    rawSamples.add(new float[]{elapsed, mag});
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── Signal extraction ─────────────────────────────────────────────────────

    /**
     * Build the 1-D signal array that will be fed to ACF or FFT.
     *
     * GYROSCOPE: return the SIGNED values of the axis with the highest variance.
     *   Rationale: the dominant rotation axis is the one that encodes cadence.
     *   Taking magnitude would fold the negative half-cycle (leg going down)
     *   onto the positive half-cycle (leg going up) → apparent frequency doubles.
     *   Using the signed dominant axis gives one full cycle per pedal revolution.
     *
     * ACCELEROMETER: return the magnitude sqrt(x²+y²+z²).
     *   Gravity creates a DC offset on the vertical axis that makes one
     *   half-cycle larger than the other → no frequency doubling.
     *   Magnitude is robust to arbitrary phone orientation in the pocket.
     */
    private float[] extractSignal() {
        float[] sigX = unroll(circX);
        float[] sigY = unroll(circY);
        float[] sigZ = unroll(circZ);

        if (useGyro) {
            // Pick the axis with the highest variance (= dominant rotation axis)
            float varX = variance(sigX);
            float varY = variance(sigY);
            float varZ = variance(sigZ);
            if (varX >= varY && varX >= varZ) return sigX;
            if (varY >= varX && varY >= varZ) return sigY;
            return sigZ;
        } else {
            // Accelerometer: magnitude preserves fundamental frequency
            float[] mag = new float[BUFFER_SIZE];
            for (int i = 0; i < BUFFER_SIZE; i++)
                mag[i] = (float) Math.sqrt(sigX[i]*sigX[i] + sigY[i]*sigY[i] + sigZ[i]*sigZ[i]);
            return mag;
        }
    }

    // ── DSP core ──────────────────────────────────────────────────────────────

    private void processBuffer() {
        float[] signal = extractSignal();

        // DC removal (removes gravity bias for accel; static drift for gyro)
        float mean = 0f;
        for (float v : signal) mean += v;
        mean /= BUFFER_SIZE;
        for (int i = 0; i < BUFFER_SIZE; i++) signal[i] -= mean;

        // Hann window — reduces spectral leakage and ACF boundary artefacts
        for (int i = 0; i < BUFFER_SIZE; i++)
            signal[i] *= 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (BUFFER_SIZE - 1)));

        if (useAcf) {
            processAcf(signal);
        } else {
            processSpectral(signal);
        }
    }

    // ── ACF method ────────────────────────────────────────────────────────────

    private void processAcf(float[] signal) {
        float energy = 0f;
        for (float v : signal) energy += v * v;
        if (energy < 1e-9f) { publish(0f, 0f, false); return; }

        // Lag range:
        //   lagMin = ceil(60/RPM_MAX * sr)  — shortest period at highest cadence
        //   lagMax = floor(60/RPM_MIN * sr) — longest period at lowest cadence
        final float sr     = measuredSampleRate;          // true Hz (adaptive)
        final int lagMin   = (int) Math.ceil (60f / RPM_MAX * sr);
        final int lagMax   = (int) Math.floor(60f / RPM_MIN * sr);
        final int lagCheck = lagMax * 2;  // for second-period criterion

        float[] acf = new float[lagCheck + 1];
        for (int lag = 1; lag <= lagCheck; lag++) {
            float sum = 0f;
            for (int n = 0; n < BUFFER_SIZE; n++)
                sum += signal[n] * signal[(n + lag) % BUFFER_SIZE];
            acf[lag] = sum / energy;
        }

        int   peakLag = lagMin;
        float peakAcf = -Float.MAX_VALUE;
        for (int lag = lagMin; lag <= lagMax; lag++)
            if (acf[lag] > peakAcf) { peakAcf = acf[lag]; peakLag = lag; }

        if (peakAcf < ACF_DETECT) { publish(0f, 0f, false); return; }

        // Parabolic sub-sample interpolation
        float refinedLag = peakLag;
        if (peakLag > lagMin && peakLag < lagMax) {
            float y0 = acf[peakLag-1], y1 = acf[peakLag], y2 = acf[peakLag+1];
            float denom = 2f * (y0 - 2f*y1 + y2);
            if (Math.abs(denom) > 1e-9f)
                refinedLag = peakLag - (y2-y0) / denom;
        }

        float rpm = Math.max(RPM_MIN, Math.min(RPM_MAX, 60f * sr / refinedLag));

        boolean acfPass = peakAcf >= ACF_STABLE;

        // Criterion 2: second period — true cadence repeats at 2× lag
        boolean period2Pass = false;
        int lag2 = Math.round(refinedLag * 2f);
        if (lag2 <= lagCheck) period2Pass = acf[lag2] >= ACF_PERIOD2_MIN;

        publishWithTemporal(rpm, acfPass, period2Pass,
                Math.min(1f, (peakAcf - ACF_DETECT) / (ACF_STABLE - ACF_DETECT)),
                period2Pass ? 1f : 0.3f);
    }

    // ── Spectral method ───────────────────────────────────────────────────────

    private void processSpectral(float[] signal) {
        float[] power = fft(signal);

        final float sr = measuredSampleRate;              // true Hz (adaptive)
        int kMin = (int) Math.ceil (RPM_MIN / 60f * BUFFER_SIZE / sr);
        int kMax = (int) Math.floor(RPM_MAX / 60f * BUFFER_SIZE / sr);

        int   peakK = kMin; float peakPow = 0f;
        for (int k = kMin; k <= kMax; k++)
            if (power[k] > peakPow) { peakPow = power[k]; peakK = k; }

        float noiseSum = 0f; int noiseCnt = 0;
        for (int k = 1; k < BUFFER_SIZE/2; k++)
            if (Math.abs(k - peakK) > 2) { noiseSum += power[k]; noiseCnt++; }
        float noise = noiseCnt > 0 ? noiseSum / noiseCnt : 1f;

        float snr = peakPow / Math.max(noise, 1e-9f);
        if (snr < SNR_DETECT) { publish(0f, 0f, false); return; }

        // Parabolic sub-bin interpolation
        float refined = peakK;
        if (peakK > kMin && peakK < kMax) {
            float y0 = power[peakK-1], y1 = power[peakK], y2 = power[peakK+1];
            float denom = 2f * (y0 - 2f*y1 + y2);
            if (Math.abs(denom) > 1e-9f) refined = peakK - (y2-y0) / denom;
        }

        // Sub-harmonic guard: if f/2 is in-band and significant → we saw 2× cadence
        float refinedHalf = refined / 2f;
        int   kHalf       = Math.round(refinedHalf);
        float rpmHalf     = refinedHalf * sr / BUFFER_SIZE * 60f;
        if (kHalf >= 1 && kHalf < BUFFER_SIZE/2
                && rpmHalf >= RPM_MIN && rpmHalf <= RPM_MAX
                && power[kHalf] > noise * SUBHARMONIC_SNR) {
            refined = refinedHalf;
        }

        float rpm = Math.max(RPM_MIN, Math.min(RPM_MAX,
                refined * sr / BUFFER_SIZE * 60f));

        boolean snrPass = snr >= SNR_STABLE;

        boolean harmonicPass = false;
        int k2 = Math.round(refined * 2), k3 = Math.round(refined * 3);
        if (k2 < BUFFER_SIZE/2 && power[k2] > noise * HARMONIC_MIN_SNR) harmonicPass = true;
        if (k3 < BUFFER_SIZE/2 && power[k3] > noise * HARMONIC_MIN_SNR) harmonicPass = true;

        publishWithTemporal(rpm, snrPass, harmonicPass,
                Math.min(1f, (snr - SNR_DETECT) / (SNR_STABLE - SNR_DETECT)),
                harmonicPass ? 1f : 0.3f);
    }

    // ── Shared publish logic ──────────────────────────────────────────────────

    private void publishWithTemporal(float rpm,
                                     boolean criterion1, boolean criterion2,
                                     float score1, float score2) {
        recentRpm.addLast(rpm);
        if (recentRpm.size() > TEMPORAL_N) recentRpm.pollFirst();

        boolean temporalPass = false;
        float   temporalScore = 0.5f;
        if (recentRpm.size() >= TEMPORAL_N) {
            float sumR = 0f; for (float r : recentRpm) sumR += r;
            float meanR = sumR / recentRpm.size(), varR = 0f;
            for (float r : recentRpm) varR += (r-meanR)*(r-meanR);
            temporalPass  = (float)Math.sqrt(varR / recentRpm.size()) <= TEMPORAL_MAX_STD;
            temporalScore = temporalPass ? 1f : 0.2f;
        }

        float confidence = (float)Math.pow(score1 * score2 * temporalScore, 1.0/3.0);
        publish(rpm, confidence, criterion1 && criterion2 && temporalPass);
    }

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

        if (rideStartMs >= 0 && rpm > 0 && !paused) {
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

    // ── DSP helpers ───────────────────────────────────────────────────────────

    private float[] unroll(float[] circ) {
        float[] out = new float[BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++)
            out[i] = circ[(head + i) % BUFFER_SIZE];
        return out;
    }

    private static float variance(float[] sig) {
        float mean = 0f;
        for (float v : sig) mean += v;
        mean /= sig.length;
        float var = 0f;
        for (float v : sig) var += (v - mean) * (v - mean);
        return var / sig.length;
    }

    // ── Cooley-Tukey radix-2 FFT (N must be power of 2) ──────────────────────

    private static float[] fft(float[] input) {
        int N = input.length;
        float[] re = input.clone(), im = new float[N];

        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; }
        }

        for (int len = 2; len <= N; len <<= 1) {
            float wRe = (float)Math.cos(-2.0*Math.PI/len);
            float wIm = (float)Math.sin(-2.0*Math.PI/len);
            for (int i = 0; i < N; i += len) {
                float cRe = 1f, cIm = 0f;
                for (int j = 0; j < len/2; j++) {
                    float uRe=re[i+j], uIm=im[i+j];
                    float vRe=re[i+j+len/2]*cRe - im[i+j+len/2]*cIm;
                    float vIm=re[i+j+len/2]*cIm + im[i+j+len/2]*cRe;
                    re[i+j]=uRe+vRe; im[i+j]=uIm+vIm;
                    re[i+j+len/2]=uRe-vRe; im[i+j+len/2]=uIm-vIm;
                    float tmp=cRe*wRe-cIm*wIm; cIm=cRe*wIm+cIm*wRe; cRe=tmp;
                }
            }
        }

        float[] power = new float[N/2];
        for (int i = 0; i < N/2; i++) power[i] = re[i]*re[i] + im[i]*im[i];
        return power;
    }
}
