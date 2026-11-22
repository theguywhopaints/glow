package io.github.theguywhopaints.glow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;

/** Synthetic 64×36 frames, laid out the way the ImageReader hands them over (RGBA, 4 bytes a pixel). */
public class ColorPickerTest {

    private static final int W = 64, H = 36;

    private interface Painter { int[] rgb(int x, int y); }

    private static ColorPicker.Result frame(Painter p) {
        ByteBuffer buf = ByteBuffer.allocate(W * H * 4);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int[] c = p.rgb(x, y);
                int i = (y * W + x) * 4;
                buf.put(i, (byte) c[0]).put(i + 1, (byte) c[1]).put(i + 2, (byte) c[2]).put(i + 3, (byte) 255);
            }
        }
        return ColorPicker.analyze(buf, W, H, W * 4, 4);
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360;
        return Math.min(d, 360 - d);
    }

    private static final int[] RED = {230, 20, 20}, BLUE = {20, 40, 230}, GREEN = {30, 200, 40};
    private static final int[] BLACK = {0, 0, 0}, GREY = {128, 128, 128};

    @Test
    public void twoColours_pickTheLargerOne_notAMix() {
        // 60% blue, 40% red. The old average gave purple (~300°), a colour that is not on screen.
        ColorPicker.Result r = frame((x, y) -> x < W * 0.4 ? RED : BLUE);
        assertTrue("hue " + r.hue, hueDistance(r.hue, 235) < 15);
        assertTrue(hueDistance(r.hue, 300) > 40);
    }

    @Test
    public void letterboxBars_areIgnored() {
        // Cinema bars: top and bottom 25% black, green picture in between.
        ColorPicker.Result r = frame((x, y) -> (y < 9 || y >= 27) ? BLACK : GREEN);
        assertTrue("top " + r.top, r.top >= 8);
        assertTrue("bottom " + r.bottom, r.bottom <= 27);
        assertTrue("hue " + r.hue, hueDistance(r.hue, 124) < 10);
        assertFalse(r.dark);
    }

    @Test
    public void pillarbox_isIgnored() {
        ColorPicker.Result r = frame((x, y) -> (x < 8 || x >= 56) ? BLACK : RED);
        assertTrue(r.left >= 7 && r.right <= 56);
        assertTrue("hue " + r.hue, hueDistance(r.hue, 0) < 8);
    }

    @Test
    public void neonOnBlack_stillGlows() {
        // A small bright magenta sign on a dark street.
        ColorPicker.Result r = frame((x, y) -> (x > 40 && x < 52 && y > 6 && y < 14) ? new int[]{240, 30, 220} : new int[]{8, 8, 12});
        assertTrue("hue " + r.hue, hueDistance(r.hue, 305) < 12);
        assertTrue("level " + r.level, r.level > 0.12f);
        assertTrue("saturation " + r.saturation, r.saturation > 70);
    }

    @Test
    public void redHueAcrossZero_doesNotBecomeCyan() {
        // Half the pixels at 355°, half at 5°: the answer is red, not the 180° a naive mean gives.
        ColorPicker.Result r = frame((x, y) -> x % 2 == 0 ? new int[]{230, 20, 40} : new int[]{230, 40, 20});
        assertTrue("hue " + r.hue, hueDistance(r.hue, 0) < 10);
    }

    @Test
    public void greyPicture_givesSoftWarmWhite() {
        ColorPicker.Result r = frame((x, y) -> GREY);
        assertEquals(35, r.hue, 0.01);
        assertTrue(r.saturation <= 12);
    }

    @Test
    public void blackFrame_isDark() {
        ColorPicker.Result r = frame((x, y) -> BLACK);
        assertTrue(r.dark);
    }

    @Test
    public void skinTone_isLowSaturationOrange_notNeon() {
        ColorPicker.Result r = frame((x, y) -> new int[]{224, 172, 140});
        assertTrue("hue " + r.hue, hueDistance(r.hue, 23) < 10);
        assertTrue("saturation " + r.saturation, r.saturation < 45);
    }
}
