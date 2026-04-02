package com.velo.speedometer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Loud speech synthesis for noisy environments.
 *
 * Pipeline:
 *   TTS.synthesizeToFile() → PCM gain + soft limiter → MediaPlayer
 */
public class AudioEnhancer {

    private static final String TAG = "AudioEnhancer";
    private static final String TTS_FILE = "sb_tts_raw.wav";
    private static final String OUT_FILE = "sb_tts_enhanced.wav";

    private final Context context;
    private final TextToSpeech tts;
    private float gainDb = 12f;

    private MediaPlayer mediaPlayer;

    public AudioEnhancer(Context context, TextToSpeech tts) {
        this.context = context;
        this.tts     = tts;
    }

    public void setGainDb(float db) {
        this.gainDb = Math.max(0f, Math.min(30f, db));
    }

    public void speak(String text, Runnable onDone) {
        File rawFile = new File(context.getCacheDir(), TTS_FILE);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onError(String id) { if (onDone != null) onDone.run(); }

            @Override
            public void onDone(String id) {
                try {
                    File enhanced = processWav(rawFile, gainDb);
                    playFile(enhanced, onDone);
                } catch (Exception e) {
                    Log.e(TAG, "Processing failed", e);
                    if (onDone != null) onDone.run();
                }
            }
        });

        android.os.Bundle params = new android.os.Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "enhance");
        tts.synthesizeToFile(text, params, rawFile, "enhance");
    }

    private File processWav(File input, float gainDb) throws Exception {
        byte[] raw = readAllBytes(input);
        if (raw.length < 44) throw new Exception("WAV too small");

        byte[] header = new byte[44];
        System.arraycopy(raw, 0, header, 0, 44);

        int dataLen = raw.length - 44;
        short[] pcm = new short[dataLen / 2];
        ByteBuffer.wrap(raw, 44, dataLen)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .asShortBuffer()
                  .get(pcm);

        int sampleRate = ByteBuffer.wrap(header, 24, 4)
                                   .order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (sampleRate <= 0) sampleRate = 22050;

        float rc    = 1f / (float)(2 * Math.PI * 250.0);
        float dt    = 1f / sampleRate;
        float alpha = rc / (rc + dt);

        float[] hp = new float[pcm.length];
        hp[0] = pcm[0];
        for (int i = 1; i < pcm.length; i++) {
            hp[i] = alpha * (hp[i - 1] + pcm[i] - pcm[i - 1]);
        }

        float linGain  = (float) Math.pow(10.0, gainDb / 20.0);
        float ceiling  = 32767f * 0.95f;

        for (int i = 0; i < pcm.length; i++) {
            float s = hp[i] * linGain;
            s = (float)(Math.tanh(s / ceiling) * ceiling);
            pcm[i] = (short) Math.max(-32768, Math.min(32767, (int) s));
        }

        byte[] outPcm = new byte[pcm.length * 2];
        ByteBuffer.wrap(outPcm)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .asShortBuffer()
                  .put(pcm);

        File outFile = new File(context.getCacheDir(), OUT_FILE);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(header);
            fos.write(outPcm);
        }
        return outFile;
    }

    private void playFile(File file, Runnable onDone) {
        releasePlayer();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setVolume(1f, 1f);
            mediaPlayer.prepare();

            mediaPlayer.setOnCompletionListener(mp -> {
                releasePlayer();
                if (onDone != null) onDone.run();
            });
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Playback failed", e);
            releasePlayer();
            if (onDone != null) onDone.run();
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) { mediaPlayer.release();  mediaPlayer = null; }
    }

    public void release() {
        releasePlayer();
    }

    private static byte[] readAllBytes(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int r = fis.read(buf, read, buf.length - read);
                if (r < 0) break;
                read += r;
            }
            return buf;
        }
    }
}