package io.github.theguywhopaints.glow;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Match the bulb to the TV, one colour at a time.
 *
 * The left half of the screen shows a pure colour; the bulb shows its current guess for it. Left and
 * Right turn the bulb's hue, Up and Down its saturation, OK saves and moves on, Back goes back a step.
 */
public class CalibrationActivity extends Activity {

    private static final int BULB_BRIGHTNESS = 85;

    private SharedPreferences prefs;
    private Calibration cal;
    private int step;
    private View swatch;
    private TextView stepView, valuesView;
    private Tapo tapo;
    private JSONObject savedBulb;

    // Latest-wins: only the newest colour is sent, so holding an arrow never queues commands.
    private final ExecutorService bulbThread = Executors.newSingleThreadExecutor();
    private final AtomicReference<int[]> pending = new AtomicReference<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("glow", MODE_PRIVATE);
        cal = Calibration.load(prefs);

        // Sync would fight the calibration for the bulb.
        startService(new Intent(this, GlowService.class).setAction(GlowService.ACTION_STOP));

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(14, 11, 24));

        swatch = new View(this);
        root.addView(swatch, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(36 * d);
        panel.setPadding(pad, pad, pad, pad);
        root.addView(panel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView title = new TextView(this);
        title.setText("Match the light to the TV");
        title.setTextColor(Color.rgb(255, 179, 71));
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        panel.addView(title);

        stepView = new TextView(this);
        stepView.setTextColor(Color.WHITE);
        stepView.setTextSize(22);
        stepView.setPadding(0, Math.round(18 * d), 0, Math.round(18 * d));
        panel.addView(stepView);

        TextView help = new TextView(this);
        help.setText("Look at the light on your wall and the colour on the left.\n\n"
                + "◀ ▶   change the light's colour\n"
                + "▲ ▼   more or less colour (saturation)\n"
                + "OK     they match, next colour\n"
                + "Back   previous colour");
        help.setTextColor(Color.rgb(200, 196, 214));
        help.setTextSize(17);
        help.setLineSpacing(0, 1.25f);
        panel.addView(help);

        valuesView = new TextView(this);
        valuesView.setTextColor(Color.rgb(140, 135, 160));
        valuesView.setTextSize(14);
        valuesView.setPadding(0, Math.round(18 * d), 0, 0);
        panel.addView(valuesView);

        setContentView(root);

        SharedPreferences p = prefs;
        String ip = p.getString("ip", null), auth = p.getString("auth", null);
        if (ip == null || auth == null) {
            stepView.setText("Glow is not set up yet.");
            return;
        }
        tapo = new Tapo(ip, Tapo.fromHex(auth));
        bulbThread.execute(() -> {
            try { savedBulb = tapo.getInfo(); } catch (Exception ignored) {}
        });
        show();
    }

    private void show() {
        int anchor = Calibration.ANCHORS[step];
        swatch.setBackgroundColor(Color.HSVToColor(new float[]{anchor, 1f, 1f}));
        stepView.setText(Calibration.NAMES[step] + "   (" + (step + 1) + " of " + Calibration.ANCHORS.length + ")");
        int h = Math.round(cal.bulbHue[step]), s = Math.round(cal.bulbSat[step]);
        valuesView.setText("Light: hue " + h + "°, saturation " + s + "%");
        sendToBulb(h, s);
    }

    private void sendToBulb(int hue, int sat) {
        if (tapo == null) return;
        if (pending.getAndSet(new int[]{hue, sat}) != null) return;   // a send is already queued
        bulbThread.execute(() -> {
            int[] v = pending.getAndSet(null);
            if (v == null) return;
            try { tapo.setColor(v[0], v[1], BULB_BRIGHTNESS); } catch (Exception ignored) {}
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int fast = event.getRepeatCount() > 3 ? 3 : 1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cal.bulbHue[step] = (cal.bulbHue[step] - 2 * fast + 360) % 360; show(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cal.bulbHue[step] = (cal.bulbHue[step] + 2 * fast) % 360; show(); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                cal.bulbSat[step] = Math.min(100, cal.bulbSat[step] + 4); show(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                cal.bulbSat[step] = Math.max(20, cal.bulbSat[step] - 4); show(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() > 0) return true;
                SharedPreferences.Editor e = prefs.edit();
                cal.save(e);
                e.apply();
                if (step + 1 < Calibration.ANCHORS.length) {
                    step++;
                    show();
                } else {
                    stepView.setText("Saved. Glow now uses your room's colours.");
                    valuesView.setText("");
                    swatch.setBackgroundColor(Color.rgb(14, 11, 24));
                    finishSoon();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (step > 0) { step--; show(); } else finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void finishSoon() {
        swatch.postDelayed(this::finish, 1800);
    }

    @Override
    protected void onDestroy() {
        final JSONObject saved = savedBulb;
        final Tapo t = tapo;
        if (t != null && saved != null) {
            bulbThread.execute(() -> {
                try { t.restore(saved); } catch (Exception ignored) {}
            });
        }
        bulbThread.shutdown();
        super.onDestroy();
    }
}
