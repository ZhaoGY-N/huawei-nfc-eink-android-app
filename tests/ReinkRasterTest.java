package org.nfcresearch.qpolllab;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

public final class ReinkRasterTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Expected rejection: " + message); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        int[] palette = CalibrationPalette.defaults();
        int[] colors = {
                0xff000000, 0xffffffff, 0xfff5cd00, 0xffc80000,
                0xff808080, 0xffffd000, 0xfff07820, 0xffd99a75,
                0xff21834a, 0xff3366cc, 0xff8a4db0, 0x80402010
        };
        for (int mode = 0; mode < ReinkRaster.MODES.length; mode++) {
            byte[] codes = ReinkRaster.quantize(colors, colors.length, 1, mode, palette);
            check(codes.length == colors.length, "mode output length " + ReinkRaster.MODES[mode]);
            for (byte code : codes) check((code & 255) < 4, "mode output code range " + ReinkRaster.MODES[mode]);
            check(codes[0] == 0, "solid black endpoint " + ReinkRaster.MODES[mode]);
            check(codes[1] == 1, "solid white endpoint " + ReinkRaster.MODES[mode]);
        }

        // Each imported mode must produce a materially different recipe on a varied photo-like raster.
        int[] varied = new int[256];
        for (int i = 0; i < varied.length; i++) {
            int r = i;
            int g = 255 - i;
            int b = (i * 53) & 255;
            varied[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        byte[] gold = ReinkRaster.quantize(varied, 256, 1, ReinkRaster.MODE_GOLD, palette);
        byte[] texture = ReinkRaster.quantize(varied, 256, 1, ReinkRaster.MODE_TEXTURE, palette);
        byte[] level = ReinkRaster.quantize(varied, 256, 1, ReinkRaster.MODE_LEVEL, palette);
        byte[] ink = ReinkRaster.quantize(varied, 256, 1, ReinkRaster.MODE_INK, palette);
        check(!Arrays.equals(gold, texture), "LOMO texture recipe changes the rendered raster");
        check(!Arrays.equals(gold, level), "non-dithered levels recipe changes the rendered raster");
        check(!Arrays.equals(gold, ink), "grayscale ink recipe changes the rendered raster");

        int[] palettePixels = palette.clone();
        byte[] goldPaletteCodes = ReinkRaster.quantize(palettePixels, 4, 1, ReinkRaster.MODE_GOLD, palette);
        check(Arrays.equals(new byte[]{0, 1, 2, 3}, goldPaletteCodes), "unmodified gold mode preserves measured palette wire codes");
        for (int mode = 1; mode < ReinkRaster.MODES.length; mode++) {
            byte[] codes = ReinkRaster.quantize(palettePixels, 4, 1, mode, palette);
            for (byte code : codes) check((code & 255) < 4, "transformed mode remains within four wire codes: " + ReinkRaster.MODES[mode]);
        }

        int[] customPalette = {0xff101010, 0xffe8e8e8, 0xffd0b040, 0xff902020};
        byte[] customCodes = ReinkRaster.quantize(customPalette, 4, 1, ReinkRaster.MODE_GOLD, customPalette);
        check(Arrays.equals(new byte[]{0, 1, 2, 3}, customCodes), "custom measured palette maps to unchanged wire indices");

        int[] repeated = new int[48];
        Arrays.fill(repeated, 0xff7f8790);
        byte[] levelsA = ReinkRaster.quantize(repeated, 8, 6, ReinkRaster.MODE_LEVEL, palette);
        byte[] levelsB = ReinkRaster.quantize(repeated, 8, 6, ReinkRaster.MODE_LEVEL, palette);
        check(Arrays.equals(levelsA, levelsB), "no-dither level mode is deterministic");
        for (byte code : levelsA) check(code == levelsA[0], "level mode does not diffuse error across identical pixels");

        int[] two = {0x00000000, 0x00000000};
        byte[] transparent = ReinkRaster.quantize(two, 2, 1, ReinkRaster.MODE_LEVEL, palette);
        check(transparent[0] == 1 && transparent[1] == 1, "transparent pixels composite over white");

        rejects(() -> ReinkRaster.quantize(new int[3], 2, 2, ReinkRaster.MODE_GOLD, palette), "pixel count mismatch");
        rejects(() -> ReinkRaster.quantize(new int[1], 1, 1, 99, palette), "invalid mode");
        rejects(() -> ReinkRaster.quantize(new int[1], 1, 1, ReinkRaster.MODE_GOLD, new int[]{0, 1, 2}), "invalid palette");

        Thread.currentThread().interrupt();
        try {
            ReinkRaster.quantize(new int[4], 2, 2, ReinkRaster.MODE_GOLD, palette);
            throw new AssertionError("interrupted quantization must cancel");
        } catch (CancellationException expected) {
            check(true, "cancellation");
        } finally {
            Thread.interrupted();
        }

        System.out.println("ReinkRasterTest: PASS (four recipes, custom palette, alpha, endpoints, deterministic levels and cancellation)");
    }
}
