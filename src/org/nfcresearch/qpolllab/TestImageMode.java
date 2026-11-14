package org.nfcresearch.qpolllab;

import java.util.Arrays;

/** Built-in tests are isolated except for the dedicated measured-palette mixing and rendering-style charts. */
public enum TestImageMode {
    NONE(-1), FOUR_COLOR(-1), TONES(-1), BLACK(0), WHITE(1), YELLOW(2), RED(3), CALIBRATION(-1), MIXING(-1), STYLE_CHART(-1);
    private final int solidCode;
    TestImageMode(int solidCode){this.solidCode=solidCode;}
    public boolean fixedRaster(){return solidCode>=0||this==CALIBRATION;}
    /** Mixing/style charts are fixed artwork, but their pixels still go through a selected quantizer. */
    public boolean locksEditor(){return fixedRaster()||this==MIXING||this==STYLE_CHART;}
    public int[] calibrationPalette(int[] palette){return this==NONE||this==MIXING||this==STYLE_CHART?palette:null;}
    public byte[] fixedCodes(int width,int height){
        if(!fixedRaster())return null;
        if(width<1||height<1||(long)width*height>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid raster size");
        if(this==CALIBRATION)return CalibrationPalette.chart(width,height);
        byte[] codes=new byte[width*height];Arrays.fill(codes,(byte)solidCode);return codes;
    }
}
