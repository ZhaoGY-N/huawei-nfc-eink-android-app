package org.nfcresearch.qpolllab;

import java.util.Arrays;

/**
 * Deterministic RGB source for both multilevel test charts.
 *
 * <p>This deliberately has no Android Canvas, Paint, Shader, density, or display-list
 * dependency. The chart is generated as opaque sRGB pixels so the quantizer always receives
 * the same chromatic target data on every Android version and panel profile.
 */
public final class TestChartPattern {
    public static final int WIDTH = 552;
    public static final int HEIGHT = 768;
    public static final int[] ANCHORS = {
        0xff808080, 0xffffd000, 0xffc80000, 0xfff07820,
        0xffd99a75, 0xff21834a, 0xff3366cc, 0xff8a4db0
    };
    public static final String[] NAMES = {"灰阶", "黄", "红", "橙", "肤", "绿", "蓝", "紫"};
    private TestChartPattern() { }

    public static int[] createPixels() { return createPixels(WIDTH, HEIGHT); }

    public static int[] createPixels(int width, int height) {
        if (width < 64 || height < 160 || (long) width * height > Integer.MAX_VALUE)
            throw new IllegalArgumentException("测试色卡尺寸错误");
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, 0xffffffff);
        int left = 58, right = width - 12;
        int contentTop = 72, contentBottom = height - 62;
        int rowHeight = (contentBottom - contentTop) / ANCHORS.length;
        for (int row = 0; row < ANCHORS.length; row++) {
            int rowTop = contentTop + row * rowHeight;
            int swatchTop = rowTop + 3;
            int swatchBottom = rowTop + Math.max(12, rowHeight - 22);
            int gradientTop = swatchBottom + 4;
            int gradientBottom = Math.min(contentBottom, gradientTop + 12);
            int anchor = ANCHORS[row];
            // Nine discrete levels: white -> target -> black.
            for (int step = 0; step < 9; step++) {
                double t = step / 8.0;
                int color = t <= 0.5
                    ? interpolate(0xffffffff, anchor, t * 2.0)
                    : interpolate(anchor, 0xff000000, (t - 0.5) * 2.0);
                int x0 = left + (int) ((right - left) * (step / 9.0));
                int x1 = left + (int) ((right - left) * ((step + 1) / 9.0));
                fillRect(pixels, width, height, x0, swatchTop, x1, swatchBottom, color);
            }
            // A continuous reference ramp immediately below the discrete steps.
            for (int x = left; x < right; x++) {
                double t = (x - left) / (double) Math.max(1, right - left - 1);
                int color = t <= 0.5
                    ? interpolate(0xffffffff, anchor, t * 2.0)
                    : interpolate(anchor, 0xff000000, (t - 0.5) * 2.0);
                fillRect(pixels, width, height, x, gradientTop, x + 1, gradientBottom, color);
            }
            // A narrow solid anchor marker makes each row's hue identifiable at a glance.
            fillRect(pixels, width, height, 46, swatchTop, 54, gradientBottom, anchor);
        }
        // Four factory primaries at the bottom provide an always-visible colour reference.
        int legendTop = height - 48, legendBottom = height - 12;
        int[] primary = CalibrationPalette.defaults();
        for (int i = 0; i < 4; i++) {
            int x0 = 16 + i * (width - 32) / 4;
            int x1 = 16 + (i + 1) * (width - 32) / 4;
            fillRect(pixels, width, height, x0, legendTop, x1, legendBottom, primary[i]);
        }
        // Thin black frame, with no anti-aliased/partially-transparent pixels.
        fillRect(pixels, width, height, 0, 0, width, 2, 0xff000000);
        fillRect(pixels, width, height, 0, height - 2, width, height, 0xff000000);
        fillRect(pixels, width, height, 0, 0, 2, height, 0xff000000);
        fillRect(pixels, width, height, width - 2, 0, width, height, 0xff000000);
        return pixels;
    }

    public static int interpolate(int from, int to, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        int r = (int) Math.round(((from >>> 16) & 255) + (((to >>> 16) & 255) - ((from >>> 16) & 255)) * t);
        int g = (int) Math.round(((from >>> 8) & 255) + (((to >>> 8) & 255) - ((from >>> 8) & 255)) * t);
        int b = (int) Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static void fillRect(int[] pixels, int width, int height,
                                 int left, int top, int right, int bottom, int color) {
        int x0 = Math.max(0, Math.min(width, left));
        int y0 = Math.max(0, Math.min(height, top));
        int x1 = Math.max(x0, Math.min(width, right));
        int y1 = Math.max(y0, Math.min(height, bottom));
        for (int y = y0; y < y1; y++) Arrays.fill(pixels, y * width + x0, y * width + x1, color);
    }
}
