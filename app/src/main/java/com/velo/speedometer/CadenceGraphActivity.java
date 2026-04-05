package com.velo.speedometer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CadenceGraphActivity extends AppCompatActivity {

    private static final long REFRESH_MS = 100L; // 10 fps chart refresh

    // ── Views ─────────────────────────────────────────────────────────────────
    private AccelChartView chart;
    private TextView        tvRpm;
    private TextView        tvSamples;
    private MaterialButton  btnLive;
    private MaterialButton  btnSaveCsv;

    // ── Service binding ───────────────────────────────────────────────────────
    private SpeedometerService service;
    private boolean            bound = false;
    private CadenceDetector    detector;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service  = ((SpeedometerService.LocalBinder) b).getService();
            detector = service.getCadenceDetector();
            bound    = true;
            if (detector != null) chart.setData(detector.getRawSamples());
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            bound    = false;
            detector = null;
        }
    };

    // ── Refresh ───────────────────────────────────────────────────────────────
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshChart();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    // ── Ride-stopped broadcast ────────────────────────────────────────────────
    private final BroadcastReceiver stoppedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (SpeedometerService.ACTION_RIDE_STOPPED.equals(intent.getAction())) {
                offerSave();
            }
        }
    };

    // ── CSV save launcher ─────────────────────────────────────────────────────
    private final ActivityResultLauncher<String> saveLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onSaveUriPicked);

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cadence_graph);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_cadence_graph);
        }

        chart      = findViewById(R.id.accelChart);
        tvRpm      = findViewById(R.id.tvGraphRpm);
        tvSamples  = findViewById(R.id.tvSampleCount);
        btnLive    = findViewById(R.id.btnScrollEnd);
        btnSaveCsv = findViewById(R.id.btnSaveCsv);

        btnLive.setOnClickListener(v -> chart.scrollToEnd());
        btnSaveCsv.setOnClickListener(v -> launchSave());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to service (it may already be running)
        Intent i = new Intent(this, SpeedometerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);

        // Listen for ride-stopped broadcast
        IntentFilter f = new IntentFilter(SpeedometerService.ACTION_RIDE_STOPPED);
        registerReceiver(stoppedReceiver, f);

        // Start refresh loop
        handler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refreshRunnable);
        try { unregisterReceiver(stoppedReceiver); } catch (Exception ignored) {}
        if (bound) {
            unbindService(conn);
            bound    = false;
            detector = null;
        }
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    // ── Chart refresh ─────────────────────────────────────────────────────────

    private void refreshChart() {
        if (detector == null) return;

        // Update cadence label
        float rpm = detector.getLastRpm();
        tvRpm.setText(rpm > 0
                ? Math.round(rpm) + " RPM"
                : getString(R.string.cadence_no_signal));

        // Update sample count label
        int count;
        synchronized (detector.getRawSamples()) {
            count = detector.getRawSamples().size();
        }
        tvSamples.setText(formatDuration(count) + "  (" + count + " samples)");

        // Tick the chart (reads latest time, triggers invalidate)
        chart.tick();

        // "Live" button: highlight when NOT auto-scrolling
        btnLive.setAlpha(chart.isAutoScroll() ? 0.4f : 1f);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void offerSave() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_title)
                .setMessage(R.string.dialog_save_message)
                .setPositiveButton(R.string.dialog_save_yes, (d, w) -> launchSave())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchSave() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        saveLauncher.launch("cadence_" + ts + ".csv");
    }

    private void onSaveUriPicked(Uri uri) {
        if (uri == null || detector == null) return;

        // Snapshot data on current thread (main), write on background
        final float[][] snapshot;
        List<float[]> samples = detector.getRawSamples();
        synchronized (samples) {
            snapshot = samples.toArray(new float[0][]);
        }

        new Thread(() -> {
            boolean ok = writeCsv(uri, snapshot);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this,
                            getString(R.string.csv_saved_ok, snapshot.length),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.csv_saved_error,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private boolean writeCsv(Uri uri, float[][] snapshot) {
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             PrintWriter pw = new PrintWriter(os)) {
            pw.println("time_sec,magnitude_ms2");
            for (float[] s : snapshot) {
                pw.printf(Locale.US, "%.3f,%.4f%n", s[0], s[1]);
            }
            pw.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    /** Converts sample count to approximate ride duration string. */
    private String formatDuration(int samples) {
        int totalSec = samples / 50; // ~50 Hz
        int m = totalSec / 60, s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
