package com.velo.speedometer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Loud speech synthesis for noisy environments.
 *
 * Pipeline:
 *   TTS.synthesizeToFile() → PCM gain + soft limiter → MediaPlayer
 */
public class AudioEnhancer {

    private static final String TAG     = "AudioEnhancer";
    private static final String TTS_FILE = "sb_tts_raw.wav";
    private static final String OUT_FILE = "sb_tts_enhanced.wav";

    private final Context context;
    private final TextToSpeech tts;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private float gainDb = 12f;

    private MediaPlayer mediaPlayer;
    private volatile boolean cancelled = false;

    public AudioEnhancer(Context context, TextToSpeech tts) {
        this.context = context;
        this.tts     = tts;
    }

    /** Stop any in-progress synthesis or playback immediately. */
    public void cancel() {
        cancelled = true;
        mainHandler.post(this::releasePlayer);
    }

    public void setGainDb(float db) {
        this.gainDb = Math.max(0f, Math.min(30f, db));
    }

    public void speak(String text, Runnable onDone) {
        cancelled = false;
        File rawFile = new File(context.getCacheDir(), TTS_FILE);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}

            @Override public void onError(String id) {
                mainHandler.post(() -> { if (onDone != null) onDone.run(); });
            }

            @Override
            public void onDone(String id) {
                // Колбек приходит в потоке TTS (без Looper) — всё дальнейшее
                // делаем на главном потоке, иначе MediaPlayer не работает корректно.
                mainHandler.post(() -> {
                    if (cancelled) { if (onDone != null) onDone.run(); return; }
                    try {
                        File enhanced = processWav(rawFile, gainDb);
                        if (cancelled) { if (onDone != null) onDone.run(); return; }
                        playFile(enhanced, onDone);
                    } catch (Exception e) {
                        Log.e(TAG, "Processing failed", e);
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });

        android.os.Bundle params = new android.os.Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "enhance");
        tts.synthesizeToFile(text, params, rawFile, "enhance");
    }

    // ── WAV processing ────────────────────────────────────────────────────────

    private File processWav(File input, float gainDb) throws Exception {
        byte[] raw = readAllBytes(input);

        // Правильно ищем data-чанк: не хардкодим 44 байта.
        // Google TTS может генерировать fmt-чанк 18 байт вместо 16,
        // тогда data начинается с байта 46, а не 44.
        int dataOffset  = findChunkOffset(raw, "data");
        int sampleRate  = readSampleRate(raw);

        int dataLen = raw.length - dataOffset;
        dataLen &= ~1; // гарантируем чётность (кратность 2 байтам)
        if (dataLen <= 0) throw new Exception("Empty data chunk");

        short[] pcm = new short[dataLen / 2];
        ByteBuffer.wrap(raw, dataOffset, dataLen)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .asShortBuffer()
                  .get(pcm);

        // Высокочастотный фильтр: убираем низкочастотный гул/ветер (срез ~250 Гц)
        float rc    = 1f / (float)(2 * Math.PI * 250.0);
        float dt    = 1f / sampleRate;
        float alpha = rc / (rc + dt);

        float[] hp = new float[pcm.length];
        hp[0] = pcm[0];
        for (int i = 1; i < pcm.length; i++) {
            hp[i] = alpha * (hp[i - 1] + pcm[i] - pcm[i - 1]);
        }

        // Усиление + мягкий лимитер (tanh)
        float linGain = (float) Math.pow(10.0, gainDb / 20.0);
        float ceiling = 32767f * 0.95f;

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

        // Пишем: оригинальный заголовок (до data-чанка включительно) + новый PCM.
        // Размеры не меняются (кол-во сэмплов то же), поэтому поля size в хедере валидны.
        File outFile = new File(context.getCacheDir(), OUT_FILE);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(raw, 0, dataOffset); // RIFF + fmt + ... + "data" + dataSize
            fos.write(outPcm);
        }
        return outFile;
    }

    /**
     * Сканирует RIFF/WAVE-файл и возвращает смещение начала данных
     * (сразу после заголовка чанка "data").
     */
    private int findChunkOffset(byte[] raw, String chunkId) throws Exception {
        if (raw.length < 12) throw new Exception("WAV too small");
        if (raw[0]!='R'||raw[1]!='I'||raw[2]!='F'||raw[3]!='F')
            throw new Exception("Not a RIFF file");
        if (raw[8]!='W'||raw[9]!='A'||raw[10]!='V'||raw[11]!='E')
            throw new Exception("Not a WAVE file");

        int i = 12;
        while (i + 8 <= raw.length) {
            String id   = new String(raw, i, 4, StandardCharsets.US_ASCII);
            int    size = ByteBuffer.wrap(raw, i + 4, 4)
                                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (chunkId.equals(id)) return i + 8;
            i += 8 + size;
            if ((size & 1) != 0) i++; // WAV-чанки выровнены по словам
        }
        throw new Exception("Chunk '" + chunkId + "' not found");
    }

    /** Читает sample rate из fmt-чанка (байты 8-11 внутри fmt-данных). */
    private int readSampleRate(byte[] raw) {
        try {
            int fmtData = findChunkOffset(raw, "fmt ");
            // Структура fmt: AudioFormat(2) + Channels(2) + SampleRate(4) + ...
            return ByteBuffer.wrap(raw, fmtData + 4, 4)
                             .order(ByteOrder.LITTLE_ENDIAN).getInt();
        } catch (Exception e) {
            Log.w(TAG, "Cannot read sample rate, using 22050", e);
            return 22050;
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

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
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
    }

    public void release() {
        releasePlayer();
    }

    // ── Util ──────────────────────────────────────────────────────────────────

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