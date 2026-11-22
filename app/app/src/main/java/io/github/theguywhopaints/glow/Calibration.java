package io.github.theguywhopaints.glow;

import android.content.SharedPreferences;

/**
 * Per-room colour correction from the on-TV calibration.
 *
 * An RGB bulb does not render a hue the way the TV panel does (yellows and oranges drift most, and
 * the wall colour shifts everything), so for each anchor hue the person picks the bulb hue and
 * saturation that look the same as the TV. Hues in between are interpolated around the wheel.
 */
final class Calibration {

    /** The colours the calibration walks through, in screen hue degrees. */
    static final int[] ANCHORS = {0, 30, 55, 90, 120, 180, 205, 235, 270, 300, 330};
    static final String[] NAMES = {"Red", "Orange", "Yellow", "Lime", "Green", "Cyan", "Sky blue",
            "Blue", "Purple", "Magenta", "Pink"};

    final float[] bulbHue = new float[ANCHORS.length];
    final float[] bulbSat = new float[ANCHORS.length];   // bulb saturation that matches a fully saturated screen colour

    static Calibration load(SharedPreferences prefs) {
        Calibration c = new Calibration();
        for (int i = 0; i < ANCHORS.length; i++) {
            c.bulbHue[i] = prefs.getFloat("cal_hue_" + i, ANCHORS[i]);
            c.bulbSat[i] = prefs.getFloat("cal_sat_" + i, 100);
        }
        return c;
    }

    void save(SharedPreferences.Editor e) {
        for (int i = 0; i < ANCHORS.length; i++) {
            e.putFloat("cal_hue_" + i, bulbHue[i]);
            e.putFloat("cal_sat_" + i, bulbSat[i]);
        }
    }

    static void reset(SharedPreferences.Editor e) {
        for (int i = 0; i < ANCHORS.length; i++) {
            e.remove("cal_hue_" + i);
            e.remove("cal_sat_" + i);
        }
    }

    boolean isDefault() {
        for (int i = 0; i < ANCHORS.length; i++) {
            if (bulbHue[i] != ANCHORS[i] || bulbSat[i] != 100) return false;
        }
        return true;
    }

    /** Screen hue → bulb hue. */
    float hue(float screenHue) {
        int n = ANCHORS.length;
        float h = ((screenHue % 360) + 360) % 360;
        for (int i = 0; i < n; i++) {
            int a = ANCHORS[i], b = i + 1 < n ? ANCHORS[i + 1] : ANCHORS[0] + 360;
            float hh = h < ANCHORS[0] ? h + 360 : h;
            if (hh >= a && hh < b) {
                float t = (hh - a) / (b - a);
                float ha = bulbHue[i];
                float hb = bulbHue[(i + 1) % n];
                float diff = ((hb - ha + 540) % 360) - 180;   // shortest way round the wheel
                return ((ha + diff * t) % 360 + 360) % 360;
            }
        }
        return h;
    }

    /** Screen saturation 0-100 → bulb saturation 0-100. */
    float saturation(float screenHue, float screenSat) {
        int n = ANCHORS.length;
        float h = ((screenHue % 360) + 360) % 360;
        for (int i = 0; i < n; i++) {
            int a = ANCHORS[i], b = i + 1 < n ? ANCHORS[i + 1] : ANCHORS[0] + 360;
            float hh = h < ANCHORS[0] ? h + 360 : h;
            if (hh >= a && hh < b) {
                float t = (hh - a) / (b - a);
                float scale = (bulbSat[i] + (bulbSat[(i + 1) % n] - bulbSat[i]) * t) / 100f;
                return Math.max(0, Math.min(100, screenSat * scale));
            }
        }
        return screenSat;
    }
}
