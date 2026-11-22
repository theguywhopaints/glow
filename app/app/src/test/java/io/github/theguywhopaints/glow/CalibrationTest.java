package io.github.theguywhopaints.glow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CalibrationTest {

    private static Calibration identity() {
        Calibration c = new Calibration();
        for (int i = 0; i < Calibration.ANCHORS.length; i++) {
            c.bulbHue[i] = Calibration.ANCHORS[i];
            c.bulbSat[i] = 100;
        }
        return c;
    }

    @Test
    public void uncalibrated_isIdentity() {
        Calibration c = identity();
        for (int h = 0; h < 360; h += 7) assertEquals(h, c.hue(h), 0.01);
        assertEquals(60, c.saturation(100, 60), 0.01);
    }

    @Test
    public void correctedAnchor_isHitExactly_andNeighboursBlend() {
        Calibration c = identity();
        c.bulbHue[2] = 45;   // the person found the bulb's yellow matches at 45°, not 55°
        assertEquals(45, c.hue(55), 0.01);
        // Halfway between orange (30) and yellow (55): halfway between 30 and 45.
        assertEquals(37.5, c.hue(42.5f), 0.01);
        assertEquals(90, c.hue(90), 0.01);
    }

    @Test
    public void wrapAroundRed_staysNearRed() {
        Calibration c = identity();
        c.bulbHue[0] = 355;   // red matched a touch towards pink
        assertEquals(355, c.hue(0), 0.01);
        // Between pink (330) and red (360→355): no jump through the whole wheel.
        float mid = c.hue(345);
        assertEquals(342.5, mid, 0.01);
    }

    @Test
    public void saturationScale_interpolates() {
        Calibration c = identity();
        c.bulbSat[2] = 80;   // yellow looked too strong at full saturation
        assertEquals(80, c.saturation(55, 100), 0.01);
        assertEquals(40, c.saturation(55, 50), 0.01);
    }
}
