package com.velo.speedometer;

import java.util.ArrayDeque;
import java.util.Deque;

public class SpeedCalculator {

    private float alpha;
    private float smoothedSpeed = 0f;
    private float totalDistanceKm = 0f;
    private long  lastUpdateMs = -1;

    // For announce-on-change
    private float lastAnnouncedSpeed = Float.MIN_VALUE;
    private long  lastAnnounceMs = -1;

    // Rolling window history for avg over N minutes
    private final Deque<long[]> history = new ArrayDeque<>(3600);

    // All-time accumulator
    private double allTimeSum = 0;
    private long   allTimeCount = 0;

    public SpeedCalculator(float alpha) {
        this.alpha = clampAlpha(alpha);
    }

    public void setAlpha(float alpha) {
        this.alpha = clampAlpha(alpha);
    }

    private float clampAlpha(float a) {
        return Math.max(0.05f, Math.min(1f, a));
    }

    /** Feed GPS speed (m/s). Returns smoothed km/h. */
    public float update(float rawSpeedMs, long nowMs) {
        float rawKmh = rawSpeedMs * 3.6f;
        if (rawKmh < 1.5f) rawKmh = 0f;

        smoothedSpeed = alpha * rawKmh + (1f - alpha) * smoothedSpeed;

        if (lastUpdateMs > 0) {
            float dtHours = (nowMs - lastUpdateMs) / 3_600_000f;
            totalDistanceKm += smoothedSpeed * dtHours;
        }
        lastUpdateMs = nowMs;

        history.addLast(new long[]{nowMs, (long)(smoothedSpeed * 100)});
        allTimeSum   += smoothedSpeed;
        allTimeCount += 1;

        return smoothedSpeed;
    }

    /**
     * Rolling average.
     * @param periodMinutes  0 = whole ride, >0 = rolling window
     */
    public float getAverageSpeed(int periodMinutes) {
        if (periodMinutes == 0) {
            // whole ride
            if (allTimeCount == 0) return 0f;
            return (float)(allTimeSum / allTimeCount);
        }
        long cutoff = System.currentTimeMillis() - (long) periodMinutes * 60_000L;
        while (!history.isEmpty() && history.peekFirst()[0] < cutoff)
            history.pollFirst();
        if (history.isEmpty()) return 0f;
        double sum = 0;
        for (long[] e : history) sum += e[1];
        return (float)(sum / history.size() / 100.0);
    }

    /** True if speed changed ≥ threshold since last announce AND debounce elapsed. */
    public boolean shouldAnnounceSpeed(float thresholdKmh, long debounceMs) {
        long now = System.currentTimeMillis();
        if (lastAnnounceMs >= 0 && (now - lastAnnounceMs) < debounceMs) return false;
        if (Math.abs(smoothedSpeed - lastAnnouncedSpeed) >= thresholdKmh) {
            lastAnnouncedSpeed = smoothedSpeed;
            lastAnnounceMs = now;
            return true;
        }
        return false;
    }

    public void resetAnnouncedSpeed() {
        lastAnnouncedSpeed = Float.MIN_VALUE;
        lastAnnounceMs = -1;
    }

    public float getSmoothedSpeed()    { return smoothedSpeed; }
    public float getTotalDistanceKm()  { return totalDistanceKm; }

    public void reset() {
        smoothedSpeed   = 0f;
        totalDistanceKm = 0f;
        lastUpdateMs    = -1;
        allTimeSum      = 0;
        allTimeCount    = 0;
        resetAnnouncedSpeed();
        history.clear();
    }
}
