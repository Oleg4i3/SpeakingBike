package com.velo.speedometer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Estimates bicycle cadence (RPM) from the phone's accelerometer.
 *
 * Algorithm:
 *   1. Collect |accel| vector magnitudes into a circular buffer (~10 s).
 *   2. Every STEP_SIZE new samples (~1 s), run a Hann-windowed FFT.
 *   3. Find the dominant peak in 50–140 RPM range.
 *   4. Compare peak power to noise floor (SNR); report 0 if no clear peak.
 *   5. Parabolic interpolation for sub-bin frequency accuracy.
 *
 * No external libraries needed — includes a pure-Java Cooley-Tukey FFT.
 */
public class CadenceDetector implements SensorEventListener {

    public interface Listener {
        /** Called on the main thread. rpm == 0 means "not pedalling". */
        void onCadence(float rpm);
    }

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int   SAMPLE_RATE = 50;   // Hz assumed for SENSOR_DELAY_GAME
    private static final int   BUFFER_SIZE = 512;  // ~10.24 s, must be power of 2
    private static final int   STEP_SIZE   = 50;   // recompute every ~1 s of new data
    private static final float RPM_MIN     = 50f;
    private static final float RPM_MAX     = 140f;
    private static final float SNR_THRESHOLD = 5f; // min signal-to-noise to report cadence

    // ── State ─────────────────────────────────────────────────────────────────
    private final SensorManager sensorManager;
    private final Listener      listener;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    private final float[] circBuf   = new float[BUFFER_SIZE];
    private int           head      = 0;   // next write position in circular buffer
    private int           filled    = 0;   // how many samples collected so far
    private int           stepCount = 0;   // samples since last FFT

    private float lastRpm = 0f;

    // ── Public API ────────────────────────────────────────────────────────────

    public CadenceDetector(Context ctx, Listener listener) {
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
    }

    public void start() {
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        head      = 0;
        filled    = 0;
        stepCount = 0;
        lastRpm   = 0f;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        lastRpm = 0f;
    }

    /** Last computed cadence in RPM (0 if not pedalling). */
    public float getLastRpm() { return lastRpm; }

    // ── SensorEventListener ───────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        float ax = event.values[0];
        float ay = event.values[1];
        float az = event.values[2];
        // |a| — orientation-independent
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);

        circBuf[head] = mag;
        head = (head + 1) % BUFFER_SIZE;
        if (filled < BUFFER_SIZE) filled++;

        stepCount++;
        if (stepCount >= STEP_SIZE && filled == BUFFER_SIZE) {
            stepCount = 0;
            processBuffer();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── DSP ───────────────────────────────────────────────────────────────────

    private void processBuffer() {
        // Unroll circular buffer → linear array
        float[] signal = new float[BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            signal[i] = circBuf[(head + i) % BUFFER_SIZE];
        }

        // Remove DC offset
        float mean = 0f;
        for (float v : signal) mean += v;
        mean /= BUFFER_SIZE;
        for (int i = 0; i < BUFFER_SIZE; i++) signal[i] -= mean;

        // Hann window — suppresses spectral leakage at buffer edges
        for (int i = 0; i < BUFFER_SIZE; i++) {
            signal[i] *= 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (BUFFER_SIZE - 1)));
        }

        // FFT → power spectrum (bins 0 … N/2-1)
        float[] power = fft(signal);

        // Bin range for RPM_MIN … RPM_MAX
        // k = freq_Hz * N / sampleRate;  freq_Hz = rpm / 60
        int kMin = (int) Math.ceil (RPM_MIN / 60f * BUFFER_SIZE / SAMPLE_RATE); // ~8
        int kMax = (int) Math.floor(RPM_MAX / 60f * BUFFER_SIZE / SAMPLE_RATE); // ~23

        // Find peak
        int   peakK   = kMin;
        float peakPow = 0f;
        for (int k = kMin; k <= kMax; k++) {
            if (power[k] > peakPow) { peakPow = power[k]; peakK = k; }
        }

        // Noise floor = average power excluding ±2 bins around peak
        float noiseSum = 0f;
        int   noiseCnt = 0;
        for (int k = 1; k < BUFFER_SIZE / 2; k++) {
            if (Math.abs(k - peakK) > 2) { noiseSum += power[k]; noiseCnt++; }
        }
        float noise = noiseCnt > 0 ? noiseSum / noiseCnt : 1f;
        float snr   = peakPow / Math.max(noise, 1e-9f);

        float rpm;
        if (snr < SNR_THRESHOLD) {
            rpm = 0f; // no clear periodic signal → not pedalling
        } else {
            // Parabolic interpolation for sub-bin accuracy (~1 RPM precision)
            float refined = peakK;
            if (peakK > kMin && peakK < kMax) {
                float y0 = power[peakK - 1];
                float y1 = power[peakK];
                float y2 = power[peakK + 1];
                float denom = 2f * (y0 - 2f * y1 + y2);
                if (Math.abs(denom) > 1e-9f) {
                    refined = peakK - (y2 - y0) / denom;
                }
            }
            float freqHz = refined * SAMPLE_RATE / BUFFER_SIZE;
            rpm = freqHz * 60f;
            // Clamp to sane range
            rpm = Math.max(RPM_MIN, Math.min(RPM_MAX, rpm));
        }

        lastRpm = rpm;
        if (listener != null) {
            final float r = rpm;
            mainHandler.post(() -> listener.onCadence(r));
        }
    }

    // ── Cooley-Tukey radix-2 FFT (N must be power of 2) ──────────────────────

    /**
     * Computes power spectrum of real-valued input.
     * Returns array of length N/2: power[k] = |X[k]|²
     */
    private static float[] fft(float[] input) {
        int N = input.length;
        float[] re = input.clone();
        float[] im = new float[N];

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
            }
        }

        // Butterfly stages
        for (int len = 2; len <= N; len <<= 1) {
            double ang  = -2.0 * Math.PI / len;
            float  wRe  = (float) Math.cos(ang);
            float  wIm  = (float) Math.sin(ang);
            for (int i = 0; i < N; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int j = 0; j < len / 2; j++) {
                    float uRe = re[i + j];
                    float uIm = im[i + j];
                    float vRe = re[i + j + len / 2] * curRe - im[i + j + len / 2] * curIm;
                    float vIm = re[i + j + len / 2] * curIm + im[i + j + len / 2] * curRe;
                    re[i + j]           =  uRe + vRe;
                    im[i + j]           =  uIm + vIm;
                    re[i + j + len / 2] =  uRe - vRe;
                    im[i + j + len / 2] =  uIm - vIm;
                    float tmpRe = curRe * wRe - curIm * wIm;
                    curIm       = curRe * wIm + curIm * wRe;
                    curRe       = tmpRe;
                }
            }
        }

        float[] power = new float[N / 2];
        for (int i = 0; i < N / 2; i++) {
            power[i] = re[i] * re[i] + im[i] * im[i];
        }
        return power;
    }
}
