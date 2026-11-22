package io.github.theguywhopaints.glow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Scenes over the home network, for iPhone Shortcuts and Siri.
 *
 *   GET http://<tv>:8765/scene/<name>   run a scene (see defaultScene; movie, party, off built in)
 *   GET http://<tv>:8765/status
 *
 * Every request needs the key: the header  X-Glow-Token: <token>  or  ?key=<token>  (set over ADB with
 * SetupReceiver), and only
 * addresses on the home network are answered.
 */
public class RemoteService extends Service {

    static final int PORT = 8765;
    private static final String TAG = "GlowRemote";
    
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ServerSocket server;
    private Thread acceptThread;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Glow scenes", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "remote")
                .setContentTitle("Glow scenes are ready")
                .setSmallIcon(R.drawable.ic_glow)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(2, n);
        }
        // A scene from the launcher's lock-screen buttons (signature permission, so no token needed).
        String requested = intent == null ? null : intent.getStringExtra("run_scene");
        if (requested != null && requested.matches("[a-z0-9_-]{1,32}")) {
            JSONObject cfg = scene(requested);
            if (cfg != null) {
                Log.i(TAG, "scene " + requested + " from the home screen");
                main.post(() -> runScene(requested, cfg));
            }
        }
        if (acceptThread == null) {
            acceptThread = new Thread(this::serve, "glow-remote");
            acceptThread.start();
        }
        return START_STICKY;
    }

    private void serve() {
        try {
            server = new ServerSocket(PORT);
            Log.i(TAG, "listening on port " + PORT);
            while (!server.isClosed()) {
                Socket s = server.accept();
                try {
                    handle(s);
                } catch (Exception e) {
                    Log.w(TAG, "request failed: " + e.getMessage());
                } finally {
                    try { s.close(); } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            if (server != null && !server.isClosed()) Log.w(TAG, "server stopped", e);
        }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(3000);
        InetAddress from = s.getInetAddress();
        if (!(from.isSiteLocalAddress() || from.isLoopbackAddress() || from.isLinkLocalAddress())) {
            reply(s, 403, error("home network only"));
            return;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String token = null, line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("X-Glow-Token")) {
                token = line.substring(colon + 1).trim();
            }
        }
        String[] parts = requestLine.split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        int q = path.indexOf('?');
        if (q >= 0) {
            // The key may also come as ?key=..., so an iPhone shortcut is a single address to paste.
            for (String param : path.substring(q + 1).split("&")) {
                if (param.startsWith("key=") && token == null) token = param.substring(4);
            }
            path = path.substring(0, q);
        }

        String expected = getSharedPreferences("glow", MODE_PRIVATE).getString("remote_token", null);
        if (expected == null || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            Log.w(TAG, "refused " + path + " from " + from.getHostAddress() + ": wrong or missing token");
            reply(s, 401, error("wrong or missing X-Glow-Token"));
            return;
        }

        switch (path) {
            case "/status": {
                JSONObject o = new JSONObject();
                try {
                    o.put("glow", GlowService.status);
                    o.put("mode", GlowService.mode == null ? "off" : GlowService.mode);
                    o.put("style", prefs().getString("style", GlowService.STYLE_VIVID));
                } catch (Exception ignored) {}
                reply(s, 200, o.toString());
                break;
            }
            default: {
                JSONObject cfg = path.startsWith("/scene/") ? scene(path.substring(7)) : null;
                if (cfg == null) {
                    reply(s, 404, error("unknown command " + path));
                    break;
                }
                final String name = path.substring(7);
                main.post(() -> runScene(name, cfg));
                reply(s, 200, ok(name));
            }
        }
        Log.i(TAG, path + " from " + from.getHostAddress());
    }

    // ---- Scenes ----

    /**
     * A scene is a small JSON object, stored as prefs "scene_<name>" (set over ADB with SetupReceiver),
     * falling back to the defaults below:
     *   light: "sync" (screen sync), "music", "color" (a fixed colour), "off" (bulb back to normal), "none"
     *   style: "cinema" or "vivid" (for sync and music)
     *   hue, sat, bri: the fixed colour, for light = "color"
     *   app: package to open on the TV
     */
    static JSONObject defaultScene(String name) {
        try {
            switch (name) {
                case "movie": return new JSONObject("{\"light\":\"color\",\"hue\":0,\"sat\":100,\"bri\":50}");
                case "party": return new JSONObject("{\"light\":\"music\",\"style\":\"vivid\"}");
                case "off":   return new JSONObject("{\"light\":\"off\"}");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private JSONObject scene(String name) {
        String stored = prefs().getString("scene_" + name, null);
        if (stored != null) {
            try { return new JSONObject(stored); } catch (Exception e) { Log.w(TAG, "bad scene_" + name, e); }
        }
        return defaultScene(name);
    }

    private final java.util.concurrent.ExecutorService bulbThread =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * A scene that puts something on screen (an app, or screen sync) wakes the stick first if it is
     * asleep; waking makes Fire OS turn the TV on and switch to its input over HDMI-CEC. Light-only
     * scenes leave a sleeping TV alone.
     */
    private void runScene(String name, JSONObject cfg) {
        String app = cfg.optString("app", "");
        boolean needsScreen = !app.isEmpty() || "sync".equals(cfg.optString("light"));
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (needsScreen && pm != null && !pm.isInteractive()) {
            Log.i(TAG, "scene " + name + ": waking the TV");
            try {
                @SuppressWarnings("deprecation")
                android.os.PowerManager.WakeLock wake = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | android.os.PowerManager.ON_AFTER_RELEASE, "glow:scene");
                wake.acquire(15_000);
            } catch (Exception e) {
                // Never let a failed wake take the listener down; the scene still runs.
                Log.w(TAG, "could not wake the TV", e);
            }
            // Give the TV time to power up and switch input before the app starts.
            main.postDelayed(() -> runSceneNow(name, cfg), 3000);
            return;
        }
        runSceneNow(name, cfg);
    }

    private void runSceneNow(String name, JSONObject cfg) {
        String light = cfg.optString("light", "none");
        String style = cfg.optString("style", null);
        if (style != null) prefs().edit().putString("style", style).apply();
        boolean glowWasRunning = GlowService.mode != null;

        switch (light) {
            case "sync":
                // Screen sync needs the capture grant, which only an activity can collect (granted ahead
                // of time, so nothing shows). Glow steps aside once it has it.
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("start", "screen"));
                break;
            case "music":
                startForegroundService(new Intent(this, GlowService.class)
                        .putExtra(GlowService.EXTRA_MODE, GlowService.MODE_MUSIC));
                break;
            case "color": {
                if (glowWasRunning) stopGlow();
                final int hue = cfg.optInt("hue", 0), sat = cfg.optInt("sat", 100), bri = cfg.optInt("bri", 50);
                bulbThread.execute(() -> {
                    // Let Glow put the bulb back first, so the colour is not overwritten by its restore.
                    if (glowWasRunning) android.os.SystemClock.sleep(2000);
                    Tapo t = tapo();
                    if (t == null) return;
                    try {
                        // Remember the normal light once, so "off" can bring it back.
                        if (!prefs().contains("scene_saved")) {
                            JSONObject info = t.getInfo();
                            if (info != null) prefs().edit().putString("scene_saved", info.toString()).apply();
                        }
                        t.setColor(hue, sat, bri);
                        Log.i(TAG, "scene " + name + ": light set to hue " + hue + " sat " + sat + " bri " + bri);
                    } catch (Exception e) {
                        Log.w(TAG, "scene " + name + ": bulb not reachable", e);
                    }
                });
                break;
            }
            case "off":
                if (glowWasRunning) stopGlow();
                bulbThread.execute(() -> {
                    String saved = prefs().getString("scene_saved", null);
                    if (saved == null) return;
                    if (glowWasRunning) android.os.SystemClock.sleep(2000);
                    Tapo t = tapo();
                    if (t == null) return;
                    try {
                        t.restore(new JSONObject(saved));
                        prefs().edit().remove("scene_saved").apply();
                        Log.i(TAG, "scene " + name + ": light back to normal");
                    } catch (Exception e) {
                        Log.w(TAG, "scene " + name + ": restore failed", e);
                    }
                });
                break;
        }

        String app = cfg.optString("app", null);
        if (app != null && !app.isEmpty()) {
            // After screen sync has collected its grant and stepped aside.
            main.postDelayed(() -> openApp(app), "sync".equals(light) ? 2500 : 300);
        }
    }

    private void stopGlow() {
        startService(new Intent(this, GlowService.class).setAction(GlowService.ACTION_STOP));
    }

    private void openApp(String pkg) {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.w(TAG, "app not installed or not launchable: " + pkg);
            return;
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private Tapo tapo() {
        String ip = prefs().getString("ip", null), auth = prefs().getString("auth", null);
        return ip == null || auth == null ? null : new Tapo(ip, Tapo.fromHex(auth));
    }

    // ---- HTTP ----

    private SharedPreferences prefs() { return getSharedPreferences("glow", MODE_PRIVATE); }

    private static String ok(String scene) {
        return "{\"ok\":true,\"scene\":\"" + scene + "\"}";
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":\"" + message.replace("\"", "'") + "\"}";
    }

    private static void reply(Socket s, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    @Override
    public void onDestroy() {
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
        super.onDestroy();
    }
}
