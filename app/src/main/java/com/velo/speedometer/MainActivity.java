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

public class MainActivity extends AppCompatActivity
        implements SpeedometerService.SpeedListener {

    private static final int PERM       = 100;
    private static final int PERM_NOTIF = 101;

    private TextView       tvSpeed, tvStats, tvPauseLabel;
    private MaterialButton btnStart, btnPause, btnStop;
    private LinearLayout   llRunning;

    private SpeedometerService service;
    private boolean bound = false;

    private long lastVolDown = 0, lastVolUp = 0;
    private static final long DBL = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvSpeed      = findViewById(R.id.tvSpeed);
        tvStats      = findViewById(R.id.tvStats);
        tvPauseLabel = findViewById(R.id.tvPauseLabel);
        btnStart     = findViewById(R.id.btnStart);
        btnPause     = findViewById(R.id.btnPause);
        btnStop      = findViewById(R.id.btnStop);
        llRunning    = findViewById(R.id.llRunningButtons);

        btnStart.setOnClickListener(v -> { if (bound) service.startTracking(); });
        btnPause.setOnClickListener(v -> { if (bound) service.togglePause(); });
        btnStop .setOnClickListener(v -> { if (bound) service.stopTracking(); });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        checkPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!bound && hasPermission()) bindService();
    }

    @Override protected void onPause() {
        super.onPause();
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
            } else {
                if (t - lastVolUp < DBL) service.togglePause();
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