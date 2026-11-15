package org.nfcresearch.qpolllab;

/** Guardrails for test charts: retain pure endpoints and never silently show one-colour output. */
public final class TestChartPolicy {
    private TestChartPolicy() { }

    public static void preserveExactEndpoints(int[] source, byte[] codes) {
        if (source == null || codes == null || source.length != codes.length) {
            throw new IllegalArgumentException("测试色卡像素尺寸不匹配");
        }
        for (int i = 0; i < source.length; i++) {
            int rgb = source[i] & 0x00ffffff;
            if (rgb == 0x000000) codes[i] = 0;
            else if (rgb == 0xffffff) codes[i] = 1;
        }
    }

    public static void requireVisible(byte[] codes, String name) {
        if (codes == null || codes.length == 0) throw new IllegalArgumentException("测试色卡为空");
        int mask = 0;
        for (byte value : codes) {
            int code = value & 0xff;
            if (code > 3) throw new IllegalStateException(name + "包含无效四色编码：" + code);
            mask |= 1 << code;
            if (Integer.bitCount(mask) > 1) return;
        }
        throw new IllegalStateException(name + "量化结果只有一种颜色，已阻止黑屏预览；请检查校色色板或算法参数");
    }

    /**
     * Build the on-screen preview directly from logical pixel codes. The wire payload still
     * uses PanelType.pack(), but preview rendering must not depend on panel-specific byte
     * transposition/decode paths. This also makes test cards deterministic across A0/A1/A0X.
     */
    public static int[] previewPixels(byte[] codes, int[] palette) {
        if (codes == null || codes.length == 0) throw new IllegalArgumentException("测试色卡为空");
        CalibrationPalette.validate(palette);
        int[] result = new int[codes.length];
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i] & 0xff;
            if (code > 3) throw new IllegalStateException("测试色卡包含无效四色编码：" + code);
            result[i] = palette[code];
        }
        return result;
    }

    public static int[] histogram(byte[] codes) {
        if (codes == null) throw new IllegalArgumentException("测试色卡为空");
        int[] counts = new int[4];
        for (byte value : codes) {
            int code = value & 0xff;
            if (code > 3) throw new IllegalStateException("测试色卡包含无效四色编码：" + code);
            counts[code]++;
        }
        return counts;
    }

    public static String histogramText(byte[] codes) {
        int[] counts = histogram(codes);
        int total = Math.max(1, codes.length);
        String[] names = {"黑", "白", "黄", "红"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) result.append(" / ");
            result.append(names[i]).append(' ')
                .append(Math.round(counts[i] * 1000.0 / total) / 10.0).append('%');
        }
        return result.toString();
    }

    public static String paletteText(int[] palette) {
        CalibrationPalette.validate(palette);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < palette.length; i++) {
            if (i > 0) result.append(" / ");
            result.append(String.format(java.util.Locale.ROOT, "%06X", palette[i] & 0x00ffffff));
        }
        return result.toString();
    }

    public static void requireChromaticCodes(byte[] codes, String name) {
        int[] counts = histogram(codes);
        if (counts[2] == 0 || counts[3] == 0)
            throw new IllegalStateException(name + "没有产生黄色和红色像素；请检查校色色板的黄 / 红取样值及所选算法。当前输出：" + histogramText(codes));
    }
}
