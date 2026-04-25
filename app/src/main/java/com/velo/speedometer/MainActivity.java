package com.velo.speedometer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.RadioButton;

public class MainActivity extends AppCompatActivity
        implements SpeedometerService.SpeedListener {

    private static final int PERM       = 100;
    private static final int PERM_NOTIF = 101;

    private TextView       tvSpeed, tvStats, tvPauseLabel, tvCadence;
    private MaterialButton btnStart, btnPause, btnStop;
    private LinearLayout   llRunning;

    private SpeedometerService service;
    private boolean bound = false;

    private long lastVolDown = 0, lastVolUp = 0;

    // Metronome UI widgets
    private TextView    tvMetBpm;
    private SeekBar     sbMetBpm;
    private CheckBox    cbMetSoundStrong, cbMetSoundWeak, cbMetVibStrong, cbMetVibWeak;
    private RadioGroup  rgMetSound;
    private com.google.android.material.button.MaterialButton btnMetronome;
    private boolean     metronomeUiReady = false;
    private static final long DBL = 500;

    private final android.os.Handler cadenceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cadenceRunnable = new Runnable() {
        @Override public void run() {
            updateCadenceDisplay();
            cadenceHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvSpeed      = findViewById(R.id.tvSpeed);
        tvStats      = findViewById(R.id.tvStats);
        tvPauseLabel = findViewById(R.id.tvPauseLabel);
        tvCadence    = findViewById(R.id.tvCadence);
        btnStart     = findViewById(R.id.btnStart);
        btnPause     = findViewById(R.id.btnPause);
        btnStop      = findViewById(R.id.btnStop);
        llRunning    = findViewById(R.id.llRunningButtons);

        // Metronome widgets
        tvMetBpm         = findViewById(R.id.tvMetBpm);
        sbMetBpm         = findViewById(R.id.sbMetBpm);
        cbMetSoundStrong = findViewById(R.id.cbMetSoundStrong);
        cbMetSoundWeak   = findViewById(R.id.cbMetSoundWeak);
        cbMetVibStrong   = findViewById(R.id.cbMetVibStrong);
        cbMetVibWeak     = findViewById(R.id.cbMetVibWeak);
        rgMetSound       = findViewById(R.id.rgMetSound);
        btnMetronome     = findViewById(R.id.btnMetronome);

        sbMetBpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int bpm = p + 40;
                tvMetBpm.setText(bpm + " BPM");
                if (bound && user) service.setMetronomeBpm(bpm);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        rgMetSound.setOnCheckedChangeListener((g, id) -> pushMetParams());
        cbMetSoundStrong.setOnCheckedChangeListener((b, c) -> pushMetParams());
        cbMetSoundWeak  .setOnCheckedChangeListener((b, c) -> pushMetParams());
        cbMetVibStrong  .setOnCheckedChangeListener((b, c) -> pushMetParams());
        cbMetVibWeak    .setOnCheckedChangeListener((b, c) -> pushMetParams());

        btnMetronome.setOnClickListener(v -> { if (bound) service.toggleMetronome(); });

        metronomeUiReady = true;

        btnStart.setOnClickListener(v -> { if (bound) service.startTracking(); });
        btnPause.setOnClickListener(v -> { if (bound) service.togglePause(); });
        btnStop .setOnClickListener(v -> { if (bound) service.stopTracking(); });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnCadenceGraph).setOnClickListener(v ->
                startActivity(new Intent(this, CadenceGraphActivity.class)));

        checkPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!bound && hasPermission()) bindService();
        cadenceHandler.post(cadenceRunnable);
    }

    @Override protected void onPause() {
        super.onPause();
        cadenceHandler.removeCallbacks(cadenceRunnable);
        if (bound) {
            service.setSpeedListener(null);
            unbindService(conn);
            bound = false;
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((SpeedometerService.LocalBinder) b).getService();
            service.setSpeedListener(MainActivity.this);
            bound = true;
            refreshUI(service.getState());
            syncMetronomeUi();
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; }
    };

    private void bindService() {
        Intent i = new Intent(this, SpeedometerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onSpeedUpdate(float speedKmh, float avgKmh, float distKm) {
        runOnUiThread(() -> {
            tvSpeed.setText(String.valueOf(Math.round(speedKmh)));
            tvStats.setText(String.format(Locale.US,
                    "Avg  %.0f km/h     %.2f km", avgKmh, distKm));
        });
    }

    /** Push current UI state to service. */
    private void pushMetParams() {
        if (!bound || !metronomeUiReady) return;
        int soundType = 0;
        int cid = rgMetSound.getCheckedRadioButtonId();
        if (cid == R.id.rbMetClick) soundType = 1;
        else if (cid == R.id.rbMetBeep) soundType = 2;
        service.setMetronomeParams(soundType,
                cbMetSoundStrong.isChecked(), cbMetSoundWeak.isChecked(),
                cbMetVibStrong.isChecked(),   cbMetVibWeak.isChecked());
    }

    /** Sync metronome UI widgets from service state (called on bind/reconnect). */
    private void syncMetronomeUi() {
        if (!bound || !metronomeUiReady) return;
        int bpm = service.getMetronomeBpm();
        sbMetBpm.setProgress(bpm - 40);
        tvMetBpm.setText(bpm + " BPM");
        int st = service.getMetSoundType();
        if (st == 1) rgMetSound.check(R.id.rbMetClick);
        else if (st == 2) rgMetSound.check(R.id.rbMetBeep);
        else rgMetSound.check(R.id.rbMetMaracas);
        cbMetSoundStrong.setChecked(service.isMetSoundStrong());
        cbMetSoundWeak  .setChecked(service.isMetSoundWeak());
        cbMetVibStrong  .setChecked(service.isMetVibStrong());
        cbMetVibWeak    .setChecked(service.isMetVibWeak());
        refreshMetronomeButton(service.isMetronomePlaying());
    }

    public void onMetMinus(android.view.View v) {
        if (!bound) return;
        int bpm = Math.max(40, service.getMetronomeBpm() - 1);
        service.setMetronomeBpm(bpm);
        sbMetBpm.setProgress(bpm - 40);
        tvMetBpm.setText(bpm + " BPM");
    }

    public void onMetPlus(android.view.View v) {
        if (!bound) return;
        int bpm = Math.min(180, service.getMetronomeBpm() + 1);
        service.setMetronomeBpm(bpm);
        sbMetBpm.setProgress(bpm - 40);
        tvMetBpm.setText(bpm + " BPM");
    }

    private void refreshMetronomeButton(boolean playing) {
        if (btnMetronome == null) return;
        if (playing) {
            btnMetronome.setText("⏹  METRONOME OFF");
            btnMetronome.setBackgroundTintList(
                    androidx.core.content.ContextCompat.getColorStateList(this, R.color.colorButtonStop));
        } else {
            btnMetronome.setText("▶  METRONOME ON");
            btnMetronome.setBackgroundTintList(
                    androidx.core.content.ContextCompat.getColorStateList(this, R.color.colorButtonStart));
        }
    }

    @Override
    public void onMetronomeChanged(boolean playing) {
        runOnUiThread(() -> refreshMetronomeButton(playing));
    }

    private void updateCadenceDisplay() {
        if (!bound || service == null) return;
        CadenceDetector detector = service.getCadenceDetector();
        if (detector == null) { tvCadence.setText(""); return; }
        CadenceDetector.Result r = detector.getLastResult();
        if (r == null || (r.rpm == 0 && r.stableAvgRpm == 0)) {
            tvCadence.setText("");
            return;
        }
        if (r.stable && r.rpm > 0) {
            tvCadence.setText(Math.round(r.rpm) + " rpm");
        } else if (r.stableAvgRpm > 0) {
            tvCadence.setText("~" + Math.round(r.stableAvgRpm) + " rpm");
        } else if (r.rpm > 0) {
            tvCadence.setText("? rpm");
        } else {
            tvCadence.setText("");
        }
    }

    @Override
    public void onStateChanged(SpeedometerService.TrackState state) {
        runOnUiThread(() -> refreshUI(state));
    }

    private void refreshUI(SpeedometerService.TrackState state) {
        switch (state) {
            case STOPPED:
                btnStart.setVisibility(View.VISIBLE);
                llRunning.setVisibility(View.GONE);
                tvPauseLabel.setVisibility(View.GONE);
                tvSpeed.setText("0");
                tvStats.setText("Avg  — km/h     — km");
                break;
            case RUNNING:
                btnStart.setVisibility(View.GONE);
                llRunning.setVisibility(View.VISIBLE);
                tvPauseLabel.setVisibility(View.GONE);
                btnPause.setText(R.string.btn_pause);
                btnPause.setBackgroundTintList(ContextCompat.getColorStateList(
                        this, R.color.colorButtonPause));
                break;
            case PAUSED:
                btnStart.setVisibility(View.GONE);
                llRunning.setVisibility(View.VISIBLE);
                tvPauseLabel.setVisibility(View.VISIBLE);
                btnPause.setText(R.string.btn_resume);
                btnPause.setBackgroundTintList(ContextCompat.getColorStateList(
                        this, R.color.colorButtonStart));
                break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!bound) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP)
            return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            long t = e.getEventTime();
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (t - lastVolDown < DBL) service.announceNow(true);
                lastVolDown = t;
            } else { // VOLUME_UP
                // Single press → toggle metronome
                // Double press → toggle ride pause
                if (t - lastVolUp < DBL) {
                    service.togglePause();
                } else {
                    service.toggleMetronome();
                }
                lastVolUp = t;
            }
        }
        return true;
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermission() {
        if (hasPermission()) {
            bindService();
            requestNotifPermission();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, PERM);
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                        PERM_NOTIF);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == PERM && r.length > 0
                && r[0] == PackageManager.PERMISSION_GRANTED) {
            bindService();
            requestNotifPermission();
        }
        // PERM_NOTIF — система сама разблокирует канал после согласия
    }
}