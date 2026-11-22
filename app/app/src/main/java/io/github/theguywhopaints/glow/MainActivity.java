package io.github.theguywhopaints.glow;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Glow's screen: Screen sync, Music sync, Off, and the brightness ceiling.
 * Can also be started from ADB with `--es start screen|music|off`.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_SCREEN = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private Button brightnessButton;
    private Button styleButton;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(14, 11, 24));

        TextView title = new TextView(this);
        title.setText("Glow");
        title.setTextColor(Color.rgb(255, 179, 71));
        title.setTextSize(44);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Your Tapo light, in sync with the TV");
        sub.setTextColor(Color.rgb(170, 165, 190));
        sub.setTextSize(17);
        sub.setPadding(0, 0, 0, dp(28));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        Button screen = button("Screen sync", "Colours follow the picture");
        Button music = button("Music sync", "Pulses to the beat");
        Button off = button("Off", "Light goes back to normal");
        screen.setOnClickListener(v -> startScreen());
        music.setOnClickListener(v -> startMusic());
        off.setOnClickListener(v -> stopGlow());
        row.addView(screen);
        row.addView(music);
        row.addView(off);
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, dp(18), 0, 0);
        brightnessButton = button("", "Press to change");
        brightnessButton.setOnClickListener(v -> cycleBrightness());
        styleButton = button("", "");
        styleButton.setOnClickListener(v -> toggleStyle());
        Button calibrate = button("Calibrate colours", "Match the light to your TV");
        calibrate.setOnClickListener(v -> startActivity(new Intent(this, CalibrationActivity.class)));
        row2.addView(styleButton);
        row2.addView(brightnessButton);
        row2.addView(calibrate);
        root.addView(row2);
        showBrightness();
        showStyle();

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(200, 196, 214));
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(26), 0, 0);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(statusView);

        TextView note = new TextView(this);
        note.setText("Netflix and Prime Video are copy-protected, so during them the light holds a soft warm glow.");
        note.setTextColor(Color.rgb(120, 115, 140));
        note.setTextSize(13);
        note.setPadding(0, dp(10), 0, 0);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(note);

        setContentView(root);
        (GlowService.MODE_MUSIC.equals(GlowService.mode) ? music : screen).requestFocus();
        handleStartExtra(getIntent());
        if (getSharedPreferences("glow", MODE_PRIVATE).getString("remote_token", null) != null) {
            startForegroundService(new Intent(this, RemoteService.class));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStartExtra(intent);
    }

    private void handleStartExtra(Intent intent) {
        String start = intent == null ? null : intent.getStringExtra("start");
        if (start == null) return;
        intent.removeExtra("start");
        if ("screen".equals(start)) startScreen();
        else if ("music".equals(start)) startMusic();
        else if ("off".equals(start)) stopGlow();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            statusView.setText("● " + GlowService.status);
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void startScreen() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_SCREEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SCREEN) return;
        if (resultCode != RESULT_OK || data == null) {
            GlowService.status = "Screen permission refused";
            return;
        }
        Intent svc = new Intent(this, GlowService.class)
                .putExtra(GlowService.EXTRA_MODE, GlowService.MODE_SCREEN)
                .putExtra(GlowService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(GlowService.EXTRA_RESULT_DATA, data);
        GlowService.status = "Starting…";
        startForegroundService(svc);
        moveTaskToBack(true);   // back to what you were watching
    }

    private void startMusic() {
        GlowService.status = "Starting…";
        startForegroundService(new Intent(this, GlowService.class)
                .putExtra(GlowService.EXTRA_MODE, GlowService.MODE_MUSIC));
    }

    private void stopGlow() {
        startService(new Intent(this, GlowService.class).setAction(GlowService.ACTION_STOP));
    }

    private static final int[] LEVELS = {100, 75, 50, 25};

    private void cycleBrightness() {
        SharedPreferences prefs = getSharedPreferences("glow", MODE_PRIVATE);
        int cur = prefs.getInt("max_brightness", 100);
        int next = LEVELS[0];
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == cur) { next = LEVELS[(i + 1) % LEVELS.length]; break; }
        }
        prefs.edit().putInt("max_brightness", next).apply();
        showBrightness();
        // Restart the running mode so it picks the new ceiling up.
        if (GlowService.MODE_MUSIC.equals(GlowService.mode)) startMusic();
        else if (GlowService.MODE_SCREEN.equals(GlowService.mode)) startScreen();
    }

    private void toggleStyle() {
        SharedPreferences prefs = getSharedPreferences("glow", MODE_PRIVATE);
        String next = GlowService.STYLE_CINEMA.equals(prefs.getString("style", GlowService.STYLE_VIVID))
                ? GlowService.STYLE_VIVID : GlowService.STYLE_CINEMA;
        prefs.edit().putString("style", next).apply();
        showStyle();
        if (GlowService.MODE_MUSIC.equals(GlowService.mode)) startMusic();
        else if (GlowService.MODE_SCREEN.equals(GlowService.mode)) startScreen();
    }

    private void showStyle() {
        boolean cinema = GlowService.STYLE_CINEMA.equals(
                getSharedPreferences("glow", MODE_PRIVATE).getString("style", GlowService.STYLE_VIVID));
        styleButton.setText(cinema ? "Style: Cinema\nSoft and slow" : "Style: Vivid\nStrong and quick");
    }

    private void showBrightness() {
        int cur = getSharedPreferences("glow", MODE_PRIVATE).getInt("max_brightness", 100);
        brightnessButton.setText("Max brightness: " + cur + "%\nPress to change");
    }

    private Button button(String label, String hint) {
        Button b = new Button(this);
        b.setText(label.isEmpty() ? hint : label + "\n" + hint);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(26), dp(16), dp(26), dp(16));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.rgb(34, 29, 52));
        normal.setCornerRadius(dp(18));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.rgb(58, 46, 86));
        focused.setCornerRadius(dp(18));
        focused.setStroke(dp(3), Color.rgb(255, 179, 71));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), 0, dp(10), 0);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        return b;
    }

    private int dp(float v) { return Math.round(v * density); }
}
