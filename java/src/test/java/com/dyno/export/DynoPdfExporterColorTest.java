package com.dyno.export;

import com.itextpdf.kernel.colors.DeviceRgb;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DynoPdfExporterColorTest {

    private static final float DELTA = 0.01f;

    private static void assertChannels(DeviceRgb c, float r, float g, float b) {
        float[] channels = c.getColorValue();
        assertEquals("Red channel mismatch", r, channels[0], DELTA);
        assertEquals("Green channel mismatch", g, channels[1], DELTA);
        assertEquals("Blue channel mismatch", b, channels[2], DELTA);
    }

    @Test
    public void richPoleIsBlue() {
        // afrHeatColor(9.5) should be blue pole
        assertChannels(DynoPdfExporter.afrHeatColor(9.5), 29 / 255f, 78 / 255f, 216 / 255f);
        // afrHeatColor(5.0) should be clamped to 9.5
        assertChannels(DynoPdfExporter.afrHeatColor(5.0), 29 / 255f, 78 / 255f, 216 / 255f);
    }

    @Test
    public void leanPoleIsRed() {
        // afrHeatColor(16.5) should be red pole
        assertChannels(DynoPdfExporter.afrHeatColor(16.5), 196 / 255f, 61 / 255f, 61 / 255f);
        // afrHeatColor(20.0) should be clamped to 16.5
        assertChannels(DynoPdfExporter.afrHeatColor(20.0), 196 / 255f, 61 / 255f, 61 / 255f);
    }

    @Test
    public void midpointIsNeutral() {
        // afrHeatColor(13.35) should be neutral midpoint
        assertChannels(DynoPdfExporter.afrHeatColor(13.35), 240 / 255f, 239 / 255f, 236 / 255f);
    }

    @Test
    public void richSideIsBluerThanLeanSide() {
        DeviceRgb richSide = DynoPdfExporter.afrHeatColor(10.5);
        DeviceRgb leanSide = DynoPdfExporter.afrHeatColor(15.5);

        float[] richChannels = richSide.getColorValue();
        float[] leanChannels = leanSide.getColorValue();

        // Blue channel: rich side should be greater (bluer)
        assertTrue("Blue channel should be higher on rich side",
            richChannels[2] > leanChannels[2]);
        // Red channel: lean side should be greater (redder)
        assertTrue("Red channel should be higher on lean side",
            leanChannels[0] > richChannels[0]);
    }

    @Test
    public void leanArmRedIncreasesMonotonically() {
        // Test that blue channel is non-increasing across the range
        double[] afrValues = {13.4, 14.0, 15.0, 16.0, 16.5};
        float[] prevChannels = null;
        float tolerance = 0.001f;

        for (double afr : afrValues) {
            float[] currentChannels = DynoPdfExporter.afrHeatColor(afr).getColorValue();
            if (prevChannels != null) {
                // Blue channel should be non-increasing (monotonically)
                assertTrue("Blue channel should not increase",
                    prevChannels[2] >= currentChannels[2] - tolerance);
            }
            prevChannels = currentChannels;
        }

        // Verify that the colors at 13.4 and 16.5 are different
        float[] color13_4 = DynoPdfExporter.afrHeatColor(13.4).getColorValue();
        float[] color16_5 = DynoPdfExporter.afrHeatColor(16.5).getColorValue();

        boolean different = false;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(color13_4[i] - color16_5[i]) > DELTA) {
                different = true;
                break;
            }
        }
        assertTrue("Colors at 13.4 and 16.5 should differ", different);
    }

    @Test
    public void blendWithWhitePullsChannelsTowardOne() {
        // Blend black with 0.5 fraction of white
        DeviceRgb black = new DeviceRgb(0f, 0f, 0f);
        DeviceRgb blended = DynoPdfExporter.blendWithWhite(black, 0.5f);
        assertChannels(blended, 0.5f, 0.5f, 0.5f);

        // Blend any color with 1.0 fraction of white should give white
        DeviceRgb red = new DeviceRgb(1f, 0f, 0f);
        DeviceRgb fullyBlended = DynoPdfExporter.blendWithWhite(red, 1f);
        assertChannels(fullyBlended, 1f, 1f, 1f);
    }

    @Test
    public void malformedHexFallsBackToGray() {
        // Malformed hex strings should fall back to gray
        assertChannels(DynoPdfExporter.hexRgb("#GGGGGG"), 0.5f, 0.5f, 0.5f);
        assertChannels(DynoPdfExporter.hexRgb("zzzzzzz"), 0.5f, 0.5f, 0.5f);
    }
}
