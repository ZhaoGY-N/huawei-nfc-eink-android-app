package org.nfcresearch.qpolllab;

/** Selectable colour-mixing strategies for the four measured panel states. */
public enum ColorMixPreset {
    LEGACY_FS("原版：线性 RGB 最近色 + Floyd–Steinberg",
        "保持旧算法，作为基准。每个像素选最接近的原色，再向邻点扩散线性光误差。"),
    OKLAB_FS("感知：OKLab 最近色 + Floyd–Steinberg",
        "用 OKLab 感知色差选择原色，误差仍在线性 RGB 中扩散；适合比较色相与饱和度变化。"),
    BARYCENTRIC_FS("比例：四色比例分解 + Floyd–Steinberg",
        "先求黑、白、黄、红的最佳覆盖比例，再扩散比例误差；重点测试中间色还原。"),
    BARYCENTRIC_BAYER("比例：四色比例分解 + Bayer 8×8",
        "用 8×8 有序阈值矩阵落实四色比例；点阵规则、稳定，便于观察条纹与纹理造成的偏色。"),
    BARYCENTRIC_JJN("比例：四色比例分解 + JJN 扩散",
        "使用两行、较宽邻域的 Jarvis–Judice–Ninke 比例误差扩散，点阵更分散，但细节可能更柔。"),
    BARYCENTRIC_NONE("比例：四色比例分解 · 不抖动",
        "只输出比例最大的单一原色，不做空间混合。作为混色关闭的参照。"),
    ;

    public final String title;
    public final String description;
    ColorMixPreset(String title,String description){this.title=title;this.description=description;}
    @Override public String toString(){return title;}
    public static ColorMixPreset fromName(String name){
        if(name!=null)for(ColorMixPreset value:values())if(value.name().equals(name))return value;
        return LEGACY_FS;
    }
}
