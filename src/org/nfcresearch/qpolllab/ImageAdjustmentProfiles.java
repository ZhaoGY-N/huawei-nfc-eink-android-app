package org.nfcresearch.qpolllab;

/** Independent colour-adjustment profiles for the original pipeline and four imported recipes. */
public final class ImageAdjustmentProfiles {
    public static final int ORIGINAL = 0;
    public static final int STYLE_COUNT = 5;
    private static final String[] NAMES = {
        "原项目算法", "鎏金", "质感", "色阶", "水墨"
    };

    private ImageAdjustmentProfiles() { }

    public static int normalize(int profile) {
        return profile < 0 || profile >= STYLE_COUNT ? ORIGINAL : profile;
    }

    /** Profile 0 deliberately keeps the legacy keys so existing user settings are preserved. */
    public static String key(String baseKey, int profile) {
        if (baseKey == null || baseKey.isEmpty()) throw new IllegalArgumentException("参数键不能为空");
        int selected = normalize(profile);
        return selected == ORIGINAL ? baseKey : baseKey + "_raster_" + selected;
    }

    public static String name(int profile) {
        return NAMES[normalize(profile)];
    }
}
