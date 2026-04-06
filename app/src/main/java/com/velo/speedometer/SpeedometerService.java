package com.velo.speedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.app.KeyguardManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

public class SpeedometerService extends Service {

    private static final String TAG = "SpeakingBike";
    private static final String CHANNEL_ID = "sb_channel";
    private static final int    NOTIF_ID   = 1;

    public static final String ACTION_START    = "sb.START";
    public static final String ACTION_STOP     = "sb.STOP";
    public static final String ACTION_PAUSE    = "sb.PAUSE";
    public static final String ACTION_ANNOUNCE = "sb.ANNOUNCE";
    public static final String ACTION_RELOAD   = "sb.RELOAD";
    public static final String ACTION_RIDE_STOPPED = "sb.RIDE_STOPPED";

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public SpeedometerService getService() { return SpeedometerService.this; }
    }

    public enum TrackState { STOPPED, RUNNING, PAUSED }
    private TrackState state = TrackState.STOPPED;
    private boolean ttsReady = false;

    private LocationManager locationManager;
    private CameraManager   cameraManager;
    private CameraManager.TorchCallback torchCallback;
    private long lastTorchMs = 0;
    private static final long TORCH_DEBOUNCE_MS = 2000;

    private BroadcastReceiver screenOnReceiver;
    private long lastScreenAnnounceMs = 0;

    private TextToSpeech  tts;
    private AudioEnhancer audioEnhancer;

    // "uk" | "ru" | "en"
    private String lang = "en";

    private SpeedCalculator calculator;
    private Handler         handler;
    private Runnable        avgRunnable;
    private long            lastSpeedAnnounceMs = 0;
    private long            lastAnyAnnounceMs   = 0;

    private long slowStartMs = -1;
    private boolean autoPaused = false;   // true = пауза поставлена автоматически

    private SpeedListener listener;

    public interface SpeedListener {
        void onSpeedUpdate(float speedKmh, float avgKmh, float distanceKm);
        void onStateChanged(TrackState state);
    }

    private float   speedThresholdKmh;
    private long    speedDebounceMs;
    private long    maxSilenceMs;
    private int     avgPeriodMin;
    private int     avgIntervalMin;
    private boolean doAnnounceSpeed;
    private boolean doAnnounceAvg;
    private boolean doAnnounceDistance;
    private boolean autoPauseEnabled;
    private int     autoPauseSec;
    private boolean walkingSpeedEnabled;
    private boolean screenAnnounceEnabled;
    private int     screenAnnounceDebounceSec;
    private boolean enhancedAudioEnabled;
    private float   gainDb;
    private boolean doAnnounceCadence;
    private float   lastCadenceRpm = 0f;
    private CadenceDetector cadenceDetector;

    @Override
    public void onCreate() {
        super.onCreate();
        handler    = new Handler(Looper.getMainLooper());
        calculator = new SpeedCalculator(0.3f);
        cadenceDetector = new CadenceDetector(this, rpm -> lastCadenceRpm = rpm);
        createNotificationChannel();
        initTts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;
        switch (action) {
            case ACTION_START:    startTracking();   break;
            case ACTION_STOP:     stopTracking();    break;
            case ACTION_PAUSE:    togglePause();     break;
            case ACTION_ANNOUNCE: announceNow(true); break;
            case ACTION_RELOAD:   reloadSettings();  break;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public TrackState getState() { return state; }
    public void setSpeedListener(SpeedListener l) { listener = l; }
    public CadenceDetector getCadenceDetector() { return cadenceDetector; }

    public void startTracking() {
        if (state != TrackState.STOPPED) return;
        loadSettings();
        calculator.reset();
        lastSpeedAnnounceMs = 0;
        lastAnyAnnounceMs   = 0;
        slowStartMs         = -1;
        autoPaused          = false;
        state = TrackState.RUNNING;
        notifyStateChanged();

        Notification n = buildNotification(0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        requestLocationUpdates();
        cadenceDetector.start();
        registerTorchListener();
        if (screenAnnounceEnabled) registerScreenReceiver();
        if (doAnnounceAvg) scheduleAvgTimer();
        speak(str("Let's go", "Поїхали", "Погнали"));
    }

    public void stopTracking() {
        if (state == TrackState.STOPPED) return;
        state = TrackState.STOPPED;
        notifyStateChanged();

        if (locationManager != null) locationManager.removeUpdates(locationListener);
        cadenceDetector.stop();
        if (avgRunnable != null)     handler.removeCallbacks(avgRunnable);
        unregisterTorchListener();
        unregisterScreenReceiver();
        stopForeground(true);
        sendBroadcast(new Intent(ACTION_RIDE_STOPPED));

        if (listener != null)
            listener.onSpeedUpdate(0, 0, calculator.getTotalDistanceKm());
        speak(str("Ride stopped. Distance ", "Приїхали. Дистанція ", "Приехали. Дистанция ")
                + fmtDist(calculator.getTotalDistanceKm()));
    }

    public void togglePause() {
        if (state == TrackState.RUNNING) {
            autoPaused = false;   // ручная пауза
            state = TrackState.PAUSED;
            notifyStateChanged();
            if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
            updateNotification(calculator.getSmoothedSpeed(),
                    calculator.getAverageSpeed(avgPeriodMin),
                    calculator.getTotalDistanceKm());
            speak(str("Paused", "Паузу", "Пауза"));
        } else if (state == TrackState.PAUSED) {
            autoPaused = false;   // снятие паузы (ручное или авто)
            state = TrackState.RUNNING;
            slowStartMs = -1;
            notifyStateChanged();
            lastAnyAnnounceMs = System.currentTimeMillis();
            if (doAnnounceAvg) scheduleAvgTimer();
            updateNotification(calculator.getSmoothedSpeed(),
                    calculator.getAverageSpeed(avgPeriodMin),
                    calculator.getTotalDistanceKm());
            speak(str("Resumed", "Знову їдемо", "Продолжаем"));
        }
    }

    public void announceNow(boolean includeCurrentSpeed) {
        if (!ttsReady) return;
        long now = System.currentTimeMillis();
        if (lastAnyAnnounceMs > 0 && (now - lastAnyAnnounceMs) < 2000L) return;

        float speed = calculator.getSmoothedSpeed();
        float avg   = calculator.getAverageSpeed(avgPeriodMin);
        float dist  = calculator.getTotalDistanceKm();

        StringBuilder sb = new StringBuilder();
        if (includeCurrentSpeed && doAnnounceSpeed) {
            sb.append(fmtSpeedWithCadence(speed)).append(". ");
        }
        if (doAnnounceAvg)
            sb.append(str("Average ", "Середня ", "Средняя "))
              .append(Math.round(avg)).append(". ");
        if (doAnnounceDistance)
            sb.append(str("Distance ", "Дистанція ", "Дистанция "))
              .append(fmtDist(dist)).append(". ");

        if (sb.length() > 0) {
            speak(sb.toString().trim());
            lastAnyAnnounceMs = System.currentTimeMillis();
            lastSpeedAnnounceMs = lastAnyAnnounceMs;
        }
    }

    private void requestLocationUpdates() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f,
                    locationListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "No location permission", e);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (!location.hasSpeed()) return;

            long  now   = System.currentTimeMillis();
            float speed = calculator.update(location.getSpeed(), now);
            float avg   = calculator.getAverageSpeed(avgPeriodMin);
            float dist  = calculator.getTotalDistanceKm();

            if (listener != null) listener.onSpeedUpdate(speed, avg, dist);

            // Авто-возобновление — проверяем ДО раннего выхода по PAUSED,
            // но только если пауза была выставлена автоматически.
            if (autoPaused && state == TrackState.PAUSED && speed > 6f) {
                togglePause();   // togglePause сбросит autoPaused и уведомит UI
            }

            if (state != TrackState.RUNNING) return;

            if (autoPauseEnabled) {
                if (speed < 3f) {
                    if (slowStartMs < 0) slowStartMs = now;
                    else if ((now - slowStartMs) > (long) autoPauseSec * 1000L) {
                        slowStartMs = -1;
                        autoPause();   // объявляет и ставит авто-паузу
                        return;
                    }
                } else {
                    slowStartMs = -1;
                }
            }

            if (walkingSpeedEnabled && speed < 6f && speed > 0.5f) {
                if (doAnnounceSpeed &&
                        calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                    speak(str("Walking speed", "Швидкість пішохода", "Скорость черепахи"));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
                updateNotification(speed, avg, dist);
                return;
            }

            if (doAnnounceSpeed) {
                boolean forceByMaxSilence = maxSilenceMs > 0
                        && lastAnyAnnounceMs > 0
                        && (now - lastAnyAnnounceMs) >= maxSilenceMs;

                if (forceByMaxSilence) {
                    speak(fmtSpeedWithCadence(speed));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                } else if (calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                    speak(fmtSpeedWithCadence(speed));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
            }

            updateNotification(speed, avg, dist);
        }

        @Override public void onProviderDisabled(@NonNull String p) {
            speak(str("GPS disabled", "Треба увімкнути GPS", "GPS включи"));
        }
    };

    private void scheduleAvgTimer() {
        avgRunnable = () -> {
            if (state == TrackState.RUNNING) {
                float speed = calculator.getSmoothedSpeed();
                float avg   = calculator.getAverageSpeed(avgPeriodMin);
                float dist  = calculator.getTotalDistanceKm();
                long  now   = System.currentTimeMillis();

                StringBuilder sb = new StringBuilder();
                if (doAnnounceSpeed && lastSpeedAnnounceMs > 0
                        && (now - lastSpeedAnnounceMs) > 30_000L) {
                    sb.append(fmtSpeedWithCadence(speed)).append(". ");
                }
                if (doAnnounceAvg)
                    sb.append(str("Average ", "Середня ", "Средняя "))
                      .append(Math.round(avg)).append(". ");
                if (doAnnounceDistance)
                    sb.append(str("Distance ", "Дистанція ", "Дистанция "))
                      .append(fmtDist(dist)).append(". ");

                if (sb.length() > 0) {
                    speak(sb.toString().trim());
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
                scheduleAvgTimer();
            }
        };
        handler.postDelayed(avgRunnable, (long) avgIntervalMin * 60_000L);
    }

    private void registerTorchListener() {
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        torchCallback = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
                if (!enabled) return;
                if (state == TrackState.STOPPED) return;
                long now = System.currentTimeMillis();
                if (now - lastTorchMs < TORCH_DEBOUNCE_MS) return;
                lastTorchMs = now;
                if (state == TrackState.PAUSED) togglePause();
                else announceNow(true);
            }
        };
        handler.postDelayed(
            () -> cameraManager.registerTorchCallback(torchCallback, handler), 500);
    }

    private void unregisterTorchListener() {
        if (cameraManager != null && torchCallback != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
            torchCallback = null;
        }
    }

    private void registerScreenReceiver() {
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                if (state != TrackState.RUNNING) return;

                long now = System.currentTimeMillis();
                long debounceMs = (long) screenAnnounceDebounceSec * 1000L;

                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) return;
                    if (now - lastScreenAnnounceMs < debounceMs) return;
                    lastScreenAnnounceMs = now;
                    announceNow(true);
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    if (now - lastScreenAnnounceMs < debounceMs) return;
                    lastScreenAnnounceMs = now;
                    announceNow(true);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenOnReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Exception ignored) {}
            screenOnReceiver = null;
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;

            // Определяем язык системы и пробуем установить его в TTS
            String sysLang = Locale.getDefault().getLanguage(); // "uk", "ru", "en", ...
            Locale ttsLocale;
            if ("uk".equals(sysLang)) {
                ttsLocale = new Locale("uk");
                lang = "uk";
            } else if ("ru".equals(sysLang)) {
                ttsLocale = new Locale("ru");
                lang = "ru";
            } else {
                ttsLocale = Locale.ENGLISH;
                lang = "en";
            }

            int result = tts.setLanguage(ttsLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Запрошенный язык не установлен — откатываемся на английский
                Log.w(TAG, "TTS: language " + ttsLocale + " not supported, falling back to EN");
                lang = "en";
                result = tts.setLanguage(Locale.ENGLISH);
            }

            ttsReady = (result != TextToSpeech.LANG_MISSING_DATA
                     && result != TextToSpeech.LANG_NOT_SUPPORTED);
            audioEnhancer = new AudioEnhancer(this, tts);
            audioEnhancer.setGainDb(gainDb);
        });
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        if (state == TrackState.PAUSED) return;

        if (audioEnhancer != null) audioEnhancer.cancel();

        lastTorchMs = System.currentTimeMillis();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean screenOff = pm != null && !pm.isInteractive();

        if (enhancedAudioEnabled && screenOff && audioEnhancer != null) {
            audioEnhancer.speak(text, null);
        } else {
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "sb");
        }
    }

    // ── Локализация ───────────────────────────────────────────────────────────

    /** Выбирает строку по текущему языку TTS. */
    private String str(String en, String uk, String ru) {
        switch (lang) {
            case "uk": return uk;
            case "ru": return ru;
            default:   return en;
        }
    }

    private String fmtSpeed(float kmh) {
        return str("Speed ", "Швидкість ", "Скорость ") + Math.round(kmh);
    }

    private String fmtSpeedWithCadence(float kmh) {
        String s = fmtSpeed(kmh);
        if (doAnnounceCadence && lastCadenceRpm > 0f)
            s += ". " + str("Cadence ", "Каденс ", "Каданс ") + Math.round(lastCadenceRpm);
        return s;
    }

    private String fmtDist(float km) {
        if (km < 1f) {
            int m = Math.round(km * 1000);
            switch (lang) {
                case "uk": return m + " метрів";
                case "ru": return m + " метров";
                default:   return String.format(Locale.US, "%.0f meters", km * 1000);
            }
        }
        switch (lang) {
            case "uk": return String.format(Locale.getDefault(), "%.1f кілометрів", km);
            case "ru": return String.format(Locale.getDefault(), "%.1f километров", km);
            default:   return String.format(Locale.US, "%.1f kilometers", km);
        }
    }

    // ── Канал уведомлений ─────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SpeakingBike", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(float speed, float avg, float dist) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), f);
        PendingIntent announcePi = PendingIntent.getService(this, 1,
                new Intent(this, SpeedometerService.class).setAction(ACTION_ANNOUNCE), f);
        PendingIntent pausePi = PendingIntent.getService(this, 3,
                new Intent(this, SpeedometerService.class).setAction(ACTION_PAUSE), f);
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, SpeedometerService.class).setAction(ACTION_STOP), f);

        String stateTag = state == TrackState.PAUSED ? "⏸  " : "";
        String content  = state == TrackState.STOPPED ? "Tap to open"
                : String.format(Locale.US, "%s%.0f km/h  avg %.0f  %.2f km",
                                stateTag, speed, avg, dist);

        String pauseLabel = state == TrackState.PAUSED ? "▶ Resume" : "⏸ Pause";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚴 SpeakingBike")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_lock_silent_mode_off, "📢 Say",  announcePi)
                .addAction(android.R.drawable.ic_media_pause,          pauseLabel, pausePi)
                .addAction(android.R.drawable.ic_delete,               "■ Stop",  stopPi)
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification(float speed, float avg, float dist) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(speed, avg, dist));
    }

    private void notifyStateChanged() {
        if (listener != null) listener.onStateChanged(state);
    }

    /** Автоматическая пауза из-за долгого простоя. */
    private void autoPause() {
        if (state != TrackState.RUNNING) return;
        // speak() вызываем ДО смены состояния, иначе он блокируется проверкой PAUSED
        speak(str(
            "You've been stopped for a while. Auto-paused.",
            "Ви довго стоїте. Лог на паузі.",
            "Долго стоите. Авто-пауза."));
        autoPaused = true;
        state = TrackState.PAUSED;
        notifyStateChanged();
        if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
        updateNotification(calculator.getSmoothedSpeed(),
                calculator.getAverageSpeed(avgPeriodMin),
                calculator.getTotalDistanceKm());
    }

    /** Применяет новые настройки немедленно, не останавливая поездку. */
    public void reloadSettings() {
        if (state == TrackState.STOPPED) return;
        boolean prevScreenAnnounce = screenAnnounceEnabled;
        boolean prevDoAnnounceAvg  = doAnnounceAvg;
        loadSettings();
        // Перезапускаем avg-таймер если изменилась галка или интервал
        if (prevDoAnnounceAvg || doAnnounceAvg) {
            if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
            if (doAnnounceAvg && state == TrackState.RUNNING) scheduleAvgTimer();
        }
        // Обновляем регистрацию screen-receiver если галка изменилась
        if (prevScreenAnnounce != screenAnnounceEnabled) {
            unregisterScreenReceiver();
            if (screenAnnounceEnabled) registerScreenReceiver();
        }
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        speedThresholdKmh        = p.getFloat("speed_threshold", 5f);
        speedDebounceMs          = p.getInt("speed_debounce", 10) * 1000L;
        maxSilenceMs             = p.getInt("max_announce_interval", 60) * 1000L;
        avgPeriodMin             = p.getInt("avg_period", 10);
        avgIntervalMin           = p.getInt("avg_interval", 2);
        doAnnounceSpeed          = p.getBoolean("announce_speed",    true);
        doAnnounceAvg            = p.getBoolean("announce_avg",      true);
        doAnnounceDistance       = p.getBoolean("announce_distance", true);
        autoPauseEnabled         = p.getBoolean("auto_pause",        false);
        autoPauseSec             = p.getInt("auto_pause_sec",        20);
        walkingSpeedEnabled      = p.getBoolean("walking_speed",     false);
        screenAnnounceEnabled    = p.getBoolean("screen_announce",   true);
        screenAnnounceDebounceSec= p.getInt("screen_debounce",       15);
        enhancedAudioEnabled     = p.getBoolean("enhanced_audio",    true);
        gainDb                   = p.getFloat("gain_db",             12f);
        calculator.setAlpha(p.getFloat("ema_alpha", 0.3f));
        doAnnounceCadence        = p.getBoolean("announce_cadence", false);
        if (audioEnhancer != null) audioEnhancer.setGainDb(gainDb);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (state != TrackState.STOPPED) stopTracking();
        if (audioEnhancer != null) audioEnhancer.release();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        handler.removeCallbacksAndMessages(null);
    }
}