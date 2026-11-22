package io.github.theguywhopaints.glow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Setup over ADB (only the shell holds DUMP, so other apps cannot call this):
 *
 *   am broadcast -n io.github.theguywhopaints.glow/.SetupReceiver -a io.github.theguywhopaints.glow.SETUP \
 *       --es ip <bulb-ip> --es auth <sha256(sha1(email)+sha1(password)) as hex> --ei max_brightness 100
 *
 * The Tapo email and password themselves never reach the TV.
 */
public class SetupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences.Editor e = context.getSharedPreferences("glow", Context.MODE_PRIVATE).edit();
        String ip = intent.getStringExtra("ip");
        String auth = intent.getStringExtra("auth");
        if (ip != null) e.putString("ip", ip.trim());
        if (auth != null && auth.trim().matches("[0-9a-fA-F]{64}")) e.putString("auth", auth.trim().toLowerCase());
        if (intent.hasExtra("max_brightness")) {
            e.putInt("max_brightness", Tapo.clamp(intent.getIntExtra("max_brightness", 100), 5, 100));
        }
        // A scene:  --es scene movie --es light color|sync|music|off|none [--es style cinema|vivid]
        //           [--ei hue 0 --ei sat 100 --ei bri 50] [--es app <package>]
        String scene = intent.getStringExtra("scene");
        if (scene != null && scene.matches("[a-z0-9_-]{1,32}")) {
            org.json.JSONObject cfg = new org.json.JSONObject();
            try {
                cfg.put("light", intent.getStringExtra("light") != null ? intent.getStringExtra("light") : "none");
                if (intent.hasExtra("style")) cfg.put("style", intent.getStringExtra("style"));
                if (intent.hasExtra("hue")) cfg.put("hue", Tapo.clamp(intent.getIntExtra("hue", 0), 0, 359));
                if (intent.hasExtra("sat")) cfg.put("sat", Tapo.clamp(intent.getIntExtra("sat", 100), 0, 100));
                if (intent.hasExtra("bri")) cfg.put("bri", Tapo.clamp(intent.getIntExtra("bri", 50), 1, 100));
                if (intent.hasExtra("app")) cfg.put("app", intent.getStringExtra("app"));
            } catch (Exception ignored) {}
            e.putString("scene_" + scene, cfg.toString());
            Log.i(GlowService.TAG, "scene " + scene + " = " + cfg);
        }

        String token = intent.getStringExtra("token");
        if (token != null && token.trim().matches("[0-9a-fA-F]{32,64}")) e.putString("remote_token", token.trim());
        e.apply();
        if (context.getSharedPreferences("glow", Context.MODE_PRIVATE).getString("remote_token", null) != null) {
            context.startForegroundService(new Intent(context, RemoteService.class));
        }
        Log.i(GlowService.TAG, "setup updated (ip " + (ip != null) + ", login " + (auth != null) + ")");
        setResultCode(1);
    }
}
