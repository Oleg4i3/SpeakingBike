package com.velo.speedometer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Vibrator;
import android.os.VibrationEffect;

/**
 * PCM-based metronome audio engine.
 *
 * Generates AudioTrack buffers for 3 sound types × 2 (strong/weak beat).
 * A looping silent track keeps USAGE_MEDIA audio focus active even when the
 * metronome is paused — required so MediaSession VolumeProvider continues to
 * intercept hardware volume keys with the screen off.
 *
 * call init() once, beat() per tick, release() on destroy.
 */
public class MetronomeEngine {

    public static final int SOUND_MARACAS = 0;
    public static final int SOUND_CLICK   = 1;
    public static final int SOUND_BEEP    = 2;

    private static final int SAMPLE_RATE = 44100;
    private static final double BEAT_DURATION = 0.20; // seconds per beat buffer

    // [soundType][0=strong, 1=weak]
    private AudioTrack[][] tracks;
    private AudioTrack silentTrack;

    private final Vibrator vibrator;

    // Playback params (volatile — set from UI thread, read from beat thread)
    private volatile int     soundType   = SOUND_MARACAS;
    private volatile boolean soundStrong = true;
    private volatile boolean soundWeak   = true;
    private volatile boolean vibStrong   = false;
    private volatile boolean vibWeak     = true;

    public MetronomeEngine(Context ctx) {
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init() {
        tracks = new AudioTrack[3][2];
        for (int type = 0; type < 3; type++) {
            tracks[type][0] = buildTrack(type, true);
            tracks[type][1] = buildTrack(type, false);
        }
        silentTrack = buildSilentTrack();
        silentTrack.play(); // looping silence keeps media audio focus
    }

    public void release() {
        if (silentTrack != null) { silentTrack.stop(); silentTrack.release(); silentTrack = null; }
        if (tracks == null) return;
        for (AudioTrack[] pair : tracks)
            for (AudioTrack t : pair) { if (t != null) { t.stop(); t.release(); } }
        tracks = null;
    }

    // ── Called from beat loop thread ──────────────────────────────────────────

    /**
     * Play one beat. isStrong=true for downbeat, false for upbeat.
     * Thread-safe: only AudioTrack methods are called, all volatile reads are safe.
     */
    public void beat(boolean isStrong) {
        boolean doSound = isStrong ? soundStrong : soundWeak;
        boolean doVib   = isStrong ? vibStrong   : vibWeak;

        if (doSound && tracks != null) {
            AudioTrack t = tracks[soundType][isStrong ? 0 : 1];
            t.stop();
            t.reloadStaticData();
            t.play();
        }

        if (doVib && vibrator != null) {
            long ms = isStrong ? 150L : 40L;
            try {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        }
    }

    // ── Settings (can be changed mid-ride) ────────────────────────────────────

    public void setParams(int soundType, boolean soundStrong, boolean soundWeak,
                          boolean vibStrong, boolean vibWeak) {
        this.soundType   = soundType;
        this.soundStrong = soundStrong;
        this.soundWeak   = soundWeak;
        this.vibStrong   = vibStrong;
        this.vibWeak     = vibWeak;
    }

    // ── PCM synthesis ─────────────────────────────────────────────────────────

    private AudioTrack buildTrack(int type, boolean strong) {
        int n = (int)(BEAT_DURATION * SAMPLE_RATE);
        float[] buf = new float[n];

        switch (type) {
            case SOUND_MARACAS: fillMaracas(buf, strong); break;
            case SOUND_CLICK:   fillClick(buf, strong);   break;
            case SOUND_BEEP:    fillBeep(buf, strong);    break;
        }
        return makeStaticTrack(buf);
    }

    /** Maracas: filtered noise burst. Strong = full noise, Weak = low-passed smooth. */
    private static void fillMaracas(float[] buf, boolean strong) {
        int n = buf.length;
        double attackEnd = n * 0.4;
        if (strong) {
            for (int i = 0; i < n; i++) {
                float noise    = (float)(Math.random() * 2 - 1);
                float envelope = i < attackEnd
                        ? (float)(i / attackEnd)
                        : (float)(1.0 - (i - attackEnd) / (n - attackEnd));
                buf[i] = noise * envelope;
            }
        } else {
            // 15-tap moving-average smoothing → softer timbre
            float[] hist = new float[15];
            int   hidx = 0;
            float sum  = 0f;
            for (int i = 0; i < n; i++) {
                float noise = (float)(Math.random() * 2 - 1);
                float envelope = i < attackEnd
                        ? (float)(i / attackEnd)
                        : (float)(1.0 - (i - attackEnd) / (n - attackEnd));
                sum -= hist[hidx];
                hist[hidx] = noise;
                sum += noise;
                hidx = (hidx + 1) % 15;
                float s = (sum / 15f) * 3f;
                s = Math.max(-1f, Math.min(1f, s));
                buf[i] = s * envelope * 0.4f;
            }
        }
    }

    /** Click: damped sine + noise burst. Strong = 3 kHz, Weak = 1.2 kHz. */
    private static void fillClick(float[] buf, boolean strong) {
        double freq = strong ? 3000.0 : 1200.0;
        float  vol  = strong ? 1.0f   : 0.6f;
        for (int i = 0; i < buf.length; i++) {
            double t        = (double) i / SAMPLE_RATE;
            float  envelope = (float) Math.exp(-t * 150.0);
            if (envelope < 0.001f) continue;
            float  sine  = (float) Math.sin(2.0 * Math.PI * freq * t);
            float  noise = (float)(Math.random() * 2 - 1);
            buf[i] = (sine * 0.7f + noise * 0.3f) * envelope * vol;
        }
    }

    /** Beep: decaying sine. Strong = 1000 Hz, Weak = 800 Hz (major third). */
    private static void fillBeep(float[] buf, boolean strong) {
        double freq = strong ? 1000.0 : 800.0;
        float  vol  = strong ? 0.8f   : 0.5f;
        for (int i = 0; i < buf.length; i++) {
            double t        = (double) i / SAMPLE_RATE;
            float  envelope = (float) Math.exp(-t * 40.0);
            if (envelope < 0.001f) continue;
            buf[i] = (float)(Math.sin(2.0 * Math.PI * freq * t)) * envelope * vol;
        }
    }

    /** 1-second silent loop — keeps USAGE_MEDIA focus alive for MediaSession. */
    private static AudioTrack buildSilentTrack() {
        float[] silence = new float[SAMPLE_RATE];
        AudioTrack t = makeStaticTrack(silence);
        t.setLoopPoints(0, silence.length, -1);
        return t;
    }

    private static AudioTrack makeStaticTrack(float[] buf) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(buf.length * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
        return t;
    }
}
