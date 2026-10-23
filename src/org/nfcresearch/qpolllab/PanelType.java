package org.nfcresearch.qpolllab;

/** Huawei JD79665AA panel variants; selection is physical, never inferred from D1. */
public enum PanelType {
    A0, A1, A0X;
    public static PanelType parse(String value) {
        if("A0".equals(value))return A0;
        if("A1".equals(value))return A1;
        if("A0X".equals(value))return A0X;
        throw new IllegalArgumentException("请先选择屏幕类型 A0、A1 或 A0X");
    }
    public byte[] pack(byte[] codes) {
        byte[] linear=PanelProtocol.packCodes(codes,PanelProtocol.WIDTH,PanelProtocol.HEIGHT);
        return map(this==A0X?swapHalves(linear):linear,false);
    }
    private static byte[] swapHalves(byte[] linear){
        int half=linear.length/2;byte[] out=new byte[linear.length];
        // Linear packing stores whole 768-pixel columns. Swap before A0 interleaving.
        System.arraycopy(linear,half,out,0,half);System.arraycopy(linear,0,out,half,half);return out;
    }
    private byte[] logical(byte[] packed){byte[] raw=map(packed,true);return this==A0X?swapHalves(raw):raw;}
    private byte[] map(byte[] raw,boolean inverse) {
        if(raw.length!=PanelProtocol.IMAGE_BYTES)throw new IllegalArgumentException("屏幕数据长度错误");
        if(this==A1)return raw.clone();
        byte[] out=new byte[raw.length];
        // Transposed controller rows: first half -> even rows, second half -> reversed odd rows.
        final int rows=PanelProtocol.WIDTH,stride=PanelProtocol.HEIGHT/4;
        for(int row=0;row<rows;row++) {
            int target=row<rows/2?2*row:2*rows-1-2*row;
            System.arraycopy(raw,(inverse?target:row)*stride,out,(inverse?row:target)*stride,stride);
        }
        return out;
    }
    public int[] preview(byte[] packed) {
        return ImageRaster.preview(logical(packed),PanelProtocol.WIDTH,PanelProtocol.HEIGHT);
    }
    public int[] preview(byte[] packed,int[] palette) {
        return ImageRaster.preview(logical(packed),PanelProtocol.WIDTH,PanelProtocol.HEIGHT,palette);
    }
    /** Stored scan 00 uses bottom-up pixels; scan 01 uses top-down pixels in each row. */
    public static byte[] forScan(byte[] packed,boolean topDown) {
        if(packed.length!=PanelProtocol.IMAGE_BYTES)throw new IllegalArgumentException("屏幕数据长度错误");
        if(!topDown)return packed.clone();
        int stride=PanelProtocol.HEIGHT/4;byte[] out=new byte[packed.length];
        for(int row=0;row<PanelProtocol.WIDTH;row++)for(int i=0;i<stride;i++) {
            int b=packed[row*stride+stride-1-i]&255;
            out[row*stride+i]=(byte)(((b&3)<<6)|((b&12)<<2)|((b&48)>>2)|((b&192)>>6));
        }
        return out;
    }
}
