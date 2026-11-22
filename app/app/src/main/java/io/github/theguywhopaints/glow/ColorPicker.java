package io.github.theguywhopaints.glow;

import java.nio.ByteBuffer;

/**
 * Turns a tiny RGBA frame of the screen into one light colour.
 *
 * Averaging hues mixes colours that are not on screen (half red, half blue gives purple), so the
 * vivid pixels are sorted into a 24-bin hue histogram and the strongest group wins; the light takes
 * the mean hue of that group only. Pixels are weighted by how vivid (saturation²) and bright they
 * are, the outer border counts more than the centre (light spills from the TV's edges, as with
 * Ambilight), and letterbox / pillarbox bars are left out.
 */
final class ColorPicker {

    /** hue 0-359, saturation 0-100, light level 0-1 (before the user's maximum), dark = near-black frame. */
    static final class Result {
        float hue, saturation, level;
        float luminance;      // mean luminance of the picture area, 0-1
        float dominance;      // share of the colourful weight in the winning hue group, 0-1
        boolean dark;
        int top, bottom, left, right;   // picture area after removing black bars (for tests and logs)
    }

    private static final int BINS = 24;            // 15° each
    private static final float BAR_LUMA = 0.035f;  // rows/columns darker than this at the edges are bars
    private static final float BORDER = 0.22f;     // outer share of the picture weighted up
    private static final float BORDER_WEIGHT = 1.8f;

    private ColorPicker() {}

    static Result analyze(ByteBuffer buf, int width, int height, int rowStride, int pixelStride) {
        Result res = new Result();

        // 1. Find the picture area: trim near-black rows/columns from the edges (letterbox, pillarbox).
        int top = 0, bottom = height - 1, left = 0, right = width - 1;
        while (top < bottom && rowLuma(buf, top, width, rowStride, pixelStride) < BAR_LUMA) top++;
        while (bottom > top && rowLuma(buf, bottom, width, rowStride, pixelStride) < BAR_LUMA) bottom--;
        while (left < right && colLuma(buf, left, top, bottom, rowStride, pixelStride) < BAR_LUMA) left++;
        while (right > left && colLuma(buf, right, top, bottom, rowStride, pixelStride) < BAR_LUMA) right--;
        // A whole black frame trims to nothing: keep the full frame so it reads as dark.
        if (bottom - top < height / 4 || right - left < width / 4) {
            top = 0; bottom = height - 1; left = 0; right = width - 1;
        }
        res.top = top; res.bottom = bottom; res.left = left; res.right = right;

        float pw = right - left + 1, ph = bottom - top + 1;
        double[] binW = new double[BINS], binX = new double[BINS], binY = new double[BINS];
        double[] binS = new double[BINS], binV = new double[BINS];
        double lumSum = 0, colourWeight = 0;
        int n = 0;

        for (int y = top; y <= bottom; y++) {
            int row = y * rowStride;
            float fy = (y - top) / ph;
            for (int x = left; x <= right; x++) {
                int i = row + x * pixelStride;
                float r = (buf.get(i) & 0xff) / 255f;
                float g = (buf.get(i + 1) & 0xff) / 255f;
                float b = (buf.get(i + 2) & 0xff) / 255f;
                float max = Math.max(r, Math.max(g, b));
                float min = Math.min(r, Math.min(g, b));
                lumSum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
                n++;
                float chroma = max - min;
                if (chroma < 0.06f || max < 0.08f) continue;   // grey or too dark to have a colour

                float h;
                if (max == r) h = 60 * (((g - b) / chroma) % 6);
                else if (max == g) h = 60 * (((b - r) / chroma) + 2);
                else h = 60 * (((r - g) / chroma) + 4);
                if (h < 0) h += 360;
                float s = chroma / max;

                float fx = (x - left) / pw;
                boolean edge = fx < BORDER || fx > 1 - BORDER || fy < BORDER || fy > 1 - BORDER;
                double w = s * s * max * (edge ? BORDER_WEIGHT : 1f);

                int bin = Math.min(BINS - 1, (int) (h / (360f / BINS)));
                double rad = Math.toRadians(h);
                binW[bin] += w;
                binX[bin] += w * Math.cos(rad);
                binY[bin] += w * Math.sin(rad);
                binS[bin] += w * s;
                binV[bin] += w * max;
                colourWeight += w;
            }
        }

        double lum = n == 0 ? 0 : lumSum / n;
        res.luminance = (float) lum;
        res.dark = lum < 0.02;

        // 2. Washed-out or black-and-white picture: soft warm white.
        if (n == 0 || colourWeight / n < 0.012) {
            res.hue = 35;
            res.saturation = 10;
            res.level = (float) Math.pow(Math.min(1, lum * 1.6), 0.6);
            return res;
        }

        // 3. Strongest hue group: the best window of three neighbouring bins (45°), wrapping 359°→0°.
        int best = 0;
        double bestW = -1;
        for (int c = 0; c < BINS; c++) {
            double w = binW[(c + BINS - 1) % BINS] + binW[c] + binW[(c + 1) % BINS];
            if (w > bestW) { bestW = w; best = c; }
        }
        double gx = 0, gy = 0, gw = 0, gs = 0, gv = 0;
        for (int d = -1; d <= 1; d++) {
            int k = (best + d + BINS) % BINS;
            gx += binX[k]; gy += binY[k]; gw += binW[k]; gs += binS[k]; gv += binV[k];
        }
        double hue = Math.toDegrees(Math.atan2(gy, gx));
        if (hue < 0) hue += 360;
        res.hue = (float) hue;
        res.dominance = (float) (gw / colourWeight);
        res.saturation = (float) Math.max(20, Math.min(100, (gs / gw) * 100));

        // 4. Brightness: the picture's overall light, lifted by how bright the winning colour itself is,
        //    so a neon sign on a dark street still glows.
        double groupV = gv / gw;
        double level = 0.55 * Math.pow(Math.min(1, lum * 1.6), 0.6) + 0.45 * groupV * Math.min(1, gw / n * 6);
        res.level = (float) Math.max(0, Math.min(1, level));
        return res;
    }

    private static float rowLuma(ByteBuffer buf, int y, int width, int rowStride, int pixelStride) {
        double sum = 0;
        int row = y * rowStride;
        for (int x = 0; x < width; x++) sum += luma(buf, row + x * pixelStride);
        return (float) (sum / width);
    }

    private static float colLuma(ByteBuffer buf, int x, int top, int bottom, int rowStride, int pixelStride) {
        double sum = 0;
        for (int y = top; y <= bottom; y++) sum += luma(buf, y * rowStride + x * pixelStride);
        return (float) (sum / (bottom - top + 1));
    }

    private static float luma(ByteBuffer buf, int i) {
        return (0.2126f * (buf.get(i) & 0xff) + 0.7152f * (buf.get(i + 1) & 0xff) + 0.0722f * (buf.get(i + 2) & 0xff)) / 255f;
    }
}
