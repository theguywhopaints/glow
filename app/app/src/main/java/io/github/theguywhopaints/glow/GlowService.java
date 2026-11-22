package io.github.theguywhopaints.glow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.audiofx.Visualizer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * Keeps the bulb in step with the TV.
 *
 * Screen mode mirrors the screen into a 64×36 image a few times a second (MediaProjection; protected
 * video such as Netflix comes through black, and the light then holds a soft warm glow). Music mode
 * reads the output mix through Visualizer and pulses on the beat. A single sender thread always
 * sends the newest colour and never queues, so the bulb cannot fall behind the picture.
 */
public class GlowService extends Service {

    static final String TAG = "Glow";
    static final String MODE_SCREEN = "screen";
    static final String MODE_MUSIC = "music";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String ACTION_STOP = "io.github.theguywhopaints.glow.STOP";

    private static final int CAPTURE_W = 64, CAPTURE_H = 36;
    private static final long FRAME_INTERVAL_MS = 110;    // ~9 analyses a second
    private static final long DARK_HOLD_MS = 1500;
    static final String STYLE_VIVID = "vivid";
    static final String STYLE_CINEMA = "cinema";

    // Set from the style (see applyStyle). Vivid: strong and quick. Cinema: soft and slow.
    private long sendIntervalMs = 100;      // bulb measured at ~10 changes a second
    private float smoothing = 0.6f;         // 0 = frozen, 1 = no smoothing
    private float saturationBoost = 1.15f;
    private float minLevel = 0.10f;
    private float levelCap = 1f;
    private Calibration calibration;
    private ColorPicker.Result previous;
    private long lastDebugLog;

    static volatile String status = "Off";
    static volatile String mode;

    private Tapo tapo;
    private JSONObject savedBulbState;
    private int maxBrightness;

    private HandlerThread worker;
    private Handler workerHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Visualizer visualizer;
    private long lastFrameAt;
    private long darkSince;

    // Smoothed colour, as a vector so hue averages correctly across 359°→0°.
    private float sx, sy, sLevel;
    private boolean haveSmoothed;

    // Newest target for the sender thread.
    private final Object targetLock = new Object();
    private int tHue, tSat, tBri;
    private boolean tPending;
    private int sentHue = -1, sentSat = -1, sentBri = -1;
    private volatile boolean running;
    private Thread sender;

    // Music state
    private float energyAvg;
    private long lastBeatAt;
    private float musicHue = 0, musicLevel = 0.2f;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "TV going to sleep: restoring the bulb");
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String newMode = intent.getStringExtra(EXTRA_MODE);
        goForeground(MODE_SCREEN.equals(newMode));

        SharedPreferences prefs = getSharedPreferences("glow", MODE_PRIVATE);
        String ip = prefs.getString("ip", null);
        String hash = prefs.getString("auth", null);
        maxBrightness = prefs.getInt("max_brightness", 100);
        applyStyle(prefs.getString("style", STYLE_VIVID));
        calibration = Calibration.load(prefs);
        previous = null;
        if (calibration.isDefault()) {
            Log.i(TAG, "calibration: none yet");
        } else {
            StringBuilder sb = new StringBuilder("calibration:");
            for (int i = 0; i < Calibration.ANCHORS.length; i++) {
                sb.append(' ').append(Calibration.NAMES[i]).append(' ')
                        .append(Calibration.ANCHORS[i]).append("→").append(Math.round(calibration.bulbHue[i]))
                        .append('/').append(Math.round(calibration.bulbSat[i])).append('%');
            }
            Log.i(TAG, sb.toString());
        }
        if (ip == null || hash == null) {
            status = "Not set up: bulb address and login missing";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tapo == null || !tapo.host().equals(ip)) tapo = new Tapo(ip, Tapo.fromHex(hash));

        if (worker == null) {
            worker = new HandlerThread("glow-capture");
            worker.start();
            workerHandler = new Handler(worker.getLooper());
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
        stopSources();
        mode = newMode;
        haveSmoothed = false;
        startSender();

        if (MODE_SCREEN.equals(newMode)) {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (data == null) {
                status = "Screen permission missing";
                stopSelf();
                return START_NOT_STICKY;
            }
            startScreen(code, data);
        } else {
            startMusic();
        }
        return START_NOT_STICKY;
    }

    private void goForeground(boolean screen) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("glow", "Glow", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "glow")
                .setContentTitle("Glow is syncing your light")
                .setSmallIcon(R.drawable.ic_glow)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, screen ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, n);
        }
    }

    private void applyStyle(String style) {
        if (STYLE_CINEMA.equals(style)) {
            sendIntervalMs = 150;
            smoothing = 0.28f;
            saturationBoost = 0.9f;
            minLevel = 0.04f;
            levelCap = 0.65f;
        } else {   // vivid
            sendIntervalMs = 100;
            smoothing = 0.6f;
            saturationBoost = 1.15f;
            minLevel = 0.10f;
            levelCap = 1f;
        }
    }

    // ---- Screen sync ----

    private void startScreen(int resultCode, Intent data) {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            status = "Screen permission refused";
            stopSelf();
            return;
        }
        final MediaProjection current = projection;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // Only when the system ends this capture; a mode switch clears `projection` first.
                if (projection == current) stopSelf();
            }
        }, workerHandler);

        imageReader = ImageReader.newInstance(CAPTURE_W, CAPTURE_H, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onFrame, workerHandler);
        virtualDisplay = projection.createVirtualDisplay("glow", CAPTURE_W, CAPTURE_H, 120,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, workerHandler);
        status = "Syncing with the screen";
        Log.i(TAG, "screen sync started");
    }

    private void onFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return;
            lastFrameAt = now;
            Image.Plane plane = image.getPlanes()[0];
            ColorPicker.Result c = ColorPicker.analyze(plane.getBuffer(), image.getWidth(), image.getHeight(),
                    plane.getRowStride(), plane.getPixelStride());

            if (c.dark) {
                if (darkSince == 0) darkSince = now;
                if (now - darkSince > DARK_HOLD_MS) {
                    // Black screen, or protected video the capture cannot see: soft warm glow.
                    push(30, 70, 0.12f, true);
                    return;
                }
            } else {
                darkSince = 0;
            }
            // A scene cut (big jump in light, or one strong colour swapped for another) lands at once;
            // changes within a scene are smoothed so the light does not flicker.
            boolean cut = false;
            if (previous != null) {
                float dh = Math.abs(c.hue - previous.hue) % 360;
                dh = Math.min(dh, 360 - dh);
                cut = Math.abs(c.luminance - previous.luminance) > 0.18f
                        || (dh > 60 && c.dominance > 0.45f && previous.dominance > 0.45f);
            }
            previous = c;
            float level = Math.min(levelCap, Math.max(minLevel, c.level));
            push(c.hue, Math.min(100, c.saturation * saturationBoost), level, cut);
        } finally {
            image.close();
        }
    }

    // ---- Music sync ----

    private void startMusic() {
        try {
            visualizer = new Visualizer(0);   // 0 = the whole output mix
            visualizer.setEnabled(false);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {}

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                    onFft(fft);
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);
            visualizer.setEnabled(true);
            status = "Syncing with the music";
            Log.i(TAG, "music sync started");
        } catch (Exception e) {
            Log.w(TAG, "music capture unavailable", e);
            status = "Music sync is not available on this TV";
            stopSelf();
        }
    }

    private void onFft(byte[] fft) {
        // Bass energy from the lowest bins (bin 0 is DC).
        float bass = 0, all = 0;
        int bins = fft.length / 2;
        for (int k = 1; k < bins; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            float mag = (float) Math.hypot(re, im);
            if (k <= 6) bass += mag;
            all += mag;
        }
        long now = SystemClock.uptimeMillis();
        energyAvg = energyAvg == 0 ? bass : energyAvg * 0.92f + bass * 0.08f;
        boolean beat = bass > energyAvg * 1.35f && bass > 25 && now - lastBeatAt > 220;
        if (beat) {
            lastBeatAt = now;
            musicHue = (musicHue + 47) % 360;   // steps around the wheel, never the same colour twice in a row
            musicLevel = 1f;
        } else {
            musicLevel = Math.max(0.18f, musicLevel * 0.86f);
        }
        if (all < 5) {   // silence
            push(30, 60, 0.12f, true);
            return;
        }
        push(musicHue, 95, musicLevel, true);
    }

    // ---- Smoothing and sending ----

    /** @param direct skip smoothing (beats and the dark hold should land at once) */
    private void push(float hue, float sat, float level, boolean direct) {
        double rad = Math.toRadians(hue);
        float vx = (float) (Math.cos(rad) * sat), vy = (float) (Math.sin(rad) * sat);
        if (!haveSmoothed || direct) {
            sx = vx; sy = vy; sLevel = level; haveSmoothed = true;
        } else {
            sx += (vx - sx) * smoothing;
            sy += (vy - sy) * smoothing;
            sLevel += (level - sLevel) * smoothing;
        }
        float screenHue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
        float screenSat = (float) Math.min(100, Math.hypot(sx, sy));
        // The room's calibration: what the bulb must show to look like this screen colour.
        Calibration cal = calibration;
        int outHue = Math.round(cal != null ? cal.hue(screenHue) : screenHue) % 360;
        int outSat = Math.round(cal != null ? cal.saturation(screenHue, screenSat) : screenSat);
        long now = SystemClock.uptimeMillis();
        if (now - lastDebugLog > 3000) {
            lastDebugLog = now;
            Log.i(TAG, "screen hue " + Math.round(screenHue) + "° sat " + Math.round(screenSat)
                    + "% → bulb hue " + outHue + "° sat " + outSat + "%");
        }
        int outBri = Math.max(1, Math.round(sLevel * maxBrightness));

        synchronized (targetLock) {
            tHue = outHue; tSat = outSat; tBri = outBri;
            tPending = true;
            targetLock.notify();
        }
    }

    private void startSender() {
        if (running) return;
        running = true;
        sender = new Thread(() -> {
            try {
                savedBulbState = tapo.getInfo();
                // Only the light settings: the full answer also carries the bulb's location and Wi-Fi name.
                if (savedBulbState != null) {
                    Log.i(TAG, "bulb state saved: on=" + savedBulbState.optBoolean("device_on")
                            + " brightness=" + savedBulbState.optInt("brightness")
                            + " color_temp=" + savedBulbState.optInt("color_temp")
                            + " hue=" + savedBulbState.optInt("hue")
                            + " saturation=" + savedBulbState.optInt("saturation"));
                }
            } catch (Tapo.AuthException e) {
                status = "The bulb did not accept the Tapo login";
                running = false;
                return;
            } catch (Exception e) {
                status = "Cannot reach the bulb at " + tapo.host();
                Log.w(TAG, "bulb unreachable", e);
            }
            while (running) {
                int h, s, b;
                synchronized (targetLock) {
                    while (running && !tPending) {
                        try { targetLock.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (!running) break;
                    h = tHue; s = tSat; b = tBri;
                    tPending = false;
                }
                // Skip changes the eye would not notice; the bulb has a limited command rate.
                int dh = Math.abs(h - sentHue);
                dh = Math.min(dh, 360 - dh);
                if (sentHue >= 0 && dh < 4 && Math.abs(s - sentSat) < 5 && Math.abs(b - sentBri) < 3) continue;
                long t = SystemClock.uptimeMillis();
                try {
                    tapo.setColor(h, s, b);
                    sentHue = h; sentSat = s; sentBri = b;
                } catch (Exception e) {
                    Log.w(TAG, "bulb command failed: " + e.getMessage());
                    SystemClock.sleep(500);
                }
                long wait = sendIntervalMs - (SystemClock.uptimeMillis() - t);
                if (wait > 0) SystemClock.sleep(wait);
            }
        }, "glow-sender");
        sender.start();
    }

    // ---- Teardown ----

    private void stopSources() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); visualizer.release(); } catch (Exception ignored) {}
            visualizer = null;
        }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
    }

    @Override
    public void onDestroy() {
        mode = null;
        stopSources();
        running = false;
        synchronized (targetLock) { targetLock.notifyAll(); }
        final Tapo t = tapo;
        final JSONObject saved = savedBulbState;
        final Thread oldSender = sender;
        if (t != null && saved != null) {
            // Put the bulb back the way it was, after the last colour command has gone out.
            new Thread(() -> {
                try {
                    if (oldSender != null) oldSender.join(1500);
                    t.restore(saved);
                    Log.i(TAG, "bulb restored");
                } catch (Exception e) {
                    Log.w(TAG, "restore failed", e);
                }
            }, "glow-restore").start();
        }
        if (worker != null) {
            try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
            worker.quitSafely();
            worker = null;
        }
        if (!status.startsWith("Not") && !status.startsWith("The bulb") && !status.startsWith("Cannot")
                && !status.contains("not available") && !status.contains("permission")) {
            status = "Off";
        }
        super.onDestroy();
    }
}
