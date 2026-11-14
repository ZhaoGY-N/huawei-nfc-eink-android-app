package org.nfcresearch.qpolllab;

/**
 * Four selectable photo-rendering recipes ported from the user-supplied QPoll Lite project.
 *
 * <p>The four recipes are intentionally independent of the existing calibrated-mixing
 * experiments: they quantize to the same four wire codes (black, white, yellow, red),
 * but use the red-mean RGB distance and the Atkinson kernel used by that project.
 * A measured palette may be supplied; code indices always retain the panel's wire order.
 */
public final class ReinkRaster {
    public static final String[] MODES = {"鎏金", "质感", "色阶", "水墨"};
    public static final String[] HINTS = {
            "原图 + Atkinson 抖动 · 保留原始色调与细节",
            "LOMO 色彩矩阵 + Atkinson · 提亮主体、保留深部细节",
            "纯量化、不抖动 · 大色块与原色边界更明确",
            "BT.601 灰度 + Atkinson · 黑白素描效果"
    };
    public static final int MODE_GOLD = 0;
    public static final int MODE_TEXTURE = 1;
    public static final int MODE_LEVEL = 2;
    public static final int MODE_INK = 3;

    /** LOMO 4×5 matrix (sRGB 0–255), copied from the supplied implementation. */
    static final double[] LOMO = {
            1.7, 0.1, 0.1, 0, -73.1,
            0.0, 1.7, 0.1, 0, -73.1,
            0.0, 0.1, 1.6, 0, -73.1,
            0.0, 0.0, 0.0, 1, 0.0
    };

    /** Atkinson spreads 6/8 of the quantization error to six neighbours. */
    private static final int[][] ATKINSON = {{1, 0}, {2, 0}, {-1, 1}, {0, 1}, {1, 1}, {0, 2}};

    private ReinkRaster() { }

    /** Uses the factory default panel palette. */
    public static byte[] quantize(int[] pixels, int width, int height, int mode) {
        return quantize(pixels, width, height, mode, CalibrationPalette.defaults());
    }

    /**
     * Quantizes ARGB input to codes 0=black, 1=white, 2=yellow, 3=red.
     * The supplied palette must use this exact semantic/wire ordering.
     */
    public static byte[] quantize(int[] pixels, int width, int height, int mode, int[] palette) {
        if (pixels == null || width < 1 || height < 1 || (long) width * height != pixels.length
                || mode < 0 || mode >= MODES.length) {
            throw new IllegalArgumentException("无效的出图参数");
        }
        CalibrationPalette.validate(palette);
        double[][] channels = channels(pixels);
        double[] red = channels[0], green = channels[1], blue = channels[2];
        switch (mode) {
            case MODE_TEXTURE:
                transform(red, green, blue, LOMO);
                return dither(red, green, blue, width, height, palette);
            case MODE_LEVEL:
                return nearestOnly(red, green, blue, palette);
            case MODE_INK:
                grayscale(red, green, blue);
                return dither(red, green, blue, width, height, palette);
            case MODE_GOLD:
            default:
                return dither(red, green, blue, width, height, palette);
        }
    }

    /** Alpha is composited over white, matching the image editor's preview background. */
    private static double[][] channels(int[] pixels) {
        int count = pixels.length;
        double[] red = new double[count], green = new double[count], blue = new double[count];
        for (int i = 0; i < count; i++) {
            int pixel = pixels[i];
            double alpha = (pixel >>> 24) / 255.0, blank = 1.0 - alpha;
            red[i] = ((pixel >>> 16) & 255) * alpha + 255.0 * blank;
            green[i] = ((pixel >>> 8) & 255) * alpha + 255.0 * blank;
            blue[i] = (pixel & 255) * alpha + 255.0 * blank;
        }
        return new double[][]{red, green, blue};
    }

    private static void transform(double[] red, double[] green, double[] blue, double[] matrix) {
        for (int i = 0; i < red.length; i++) {
            double sourceRed = red[i], sourceGreen = green[i], sourceBlue = blue[i];
            red[i] = clamp(matrix[0] * sourceRed + matrix[1] * sourceGreen + matrix[2] * sourceBlue
                    + matrix[3] * 255 + matrix[4]);
            green[i] = clamp(matrix[5] * sourceRed + matrix[6] * sourceGreen + matrix[7] * sourceBlue
                    + matrix[8] * 255 + matrix[9]);
            blue[i] = clamp(matrix[10] * sourceRed + matrix[11] * sourceGreen + matrix[12] * sourceBlue
                    + matrix[13] * 255 + matrix[14]);
        }
    }

    /** ITU-R BT.601 luma, matching img.grayscale() in the supplied implementation. */
    private static void grayscale(double[] red, double[] green, double[] blue) {
        for (int i = 0; i < red.length; i++) {
            double luma = .299 * red[i] + .587 * green[i] + .114 * blue[i];
            red[i] = luma;
            green[i] = luma;
            blue[i] = luma;
        }
    }

    private static byte[] nearestOnly(double[] red, double[] green, double[] blue, int[] palette) {
        byte[] codes = new byte[red.length];
        for (int i = 0; i < red.length; i++) codes[i] = (byte) nearest(red[i], green[i], blue[i], palette);
        return codes;
    }

    private static byte[] dither(double[] red, double[] green, double[] blue, int width, int height, int[] palette) {
        int count = red.length;
        float[] errorRed = new float[count], errorGreen = new float[count], errorBlue = new float[count];
        byte[] codes = new byte[count];
        for (int row = 0; row < height; row++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                double sampleRed = clamp(red[index] + errorRed[index]);
                double sampleGreen = clamp(green[index] + errorGreen[index]);
                double sampleBlue = clamp(blue[index] + errorBlue[index]);
                int code = nearest(sampleRed, sampleGreen, sampleBlue, palette);
                codes[index] = (byte) code;
                int color = palette[code];
                float deltaRed = (float) (sampleRed - ((color >>> 16) & 255));
                float deltaGreen = (float) (sampleGreen - ((color >>> 8) & 255));
                float deltaBlue = (float) (sampleBlue - (color & 255));
                for (int[] step : ATKINSON) {
                    int targetRow = row + step[1], targetColumn = column + step[0];
                    if (targetRow >= height || targetColumn < 0 || targetColumn >= width) continue;
                    int target = targetRow * width + targetColumn;
                    errorRed[target] += deltaRed / 8;
                    errorGreen[target] += deltaGreen / 8;
                    errorBlue[target] += deltaBlue / 8;
                }
            }
        }
        return codes;
    }

    /** Red-mean weighted RGB distance used by the supplied QPoll Lite implementation. */
    static int nearest(double red, double green, double blue, int[] palette) {
        int sampleRed = (int) Math.round(clamp(red));
        int sampleGreen = (int) Math.round(clamp(green));
        int sampleBlue = (int) Math.round(clamp(blue));
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int color = palette[i];
            int deltaRed = sampleRed - ((color >>> 16) & 255);
            int deltaGreen = sampleGreen - ((color >>> 8) & 255);
            int deltaBlue = sampleBlue - (color & 255);
            int mean = (sampleRed + ((color >>> 16) & 255)) / 2;
            long distance = ((512 + mean) * (long) deltaRed * deltaRed) >> 8;
            distance += 4L * deltaGreen * deltaGreen;
            distance += ((767 - mean) * (long) deltaBlue * deltaBlue) >> 8;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(255, value)); }
}
