package org.nfcresearch.qpolllab;

/** Photo-derived appearance values. The four wire codes always remain black/white/yellow/red. */
public final class CalibrationPalette {
    private CalibrationPalette() { }
    public static int[] defaults(){return new int[]{0xff000000,0xffffffff,0xfff5cd00,0xffc80000};}
    public static double linear(int channel){double s=channel/255.0;return s<=0.04045?s/12.92:Math.pow((s+0.055)/1.055,2.4);}
    private static int encoded(double value){double s=value<=0.0031308?12.92*value:1.055*Math.pow(value,1/2.4)-0.055;return Math.max(0,Math.min(255,(int)Math.round(s*255)));}
    public static double luminance(int c){return 0.2126*linear((c>>>16)&255)+0.7152*linear((c>>>8)&255)+0.0722*linear(c&255);}
    public static void validate(int[] colors){
        if(colors==null||colors.length!=4)throw new IllegalArgumentException("需要黑、白、黄、红四种颜色");
        for(int i=0;i<4;i++){
            if((colors[i]>>>24)!=255)throw new IllegalArgumentException("色板必须是不透明颜色");
            for(int j=0;j<i;j++)if(colors[i]==colors[j])throw new IllegalArgumentException("四色不能相同，请重新取样或手调");
        }
        if(luminance(colors[1])-luminance(colors[0])<0.05)throw new IllegalArgumentException("白色应明显亮于黑色，请核对取样顺序");
        for(int shift:new int[]{16,8,0})if(((colors[1]>>>shift)&255)<=((colors[0]>>>shift)&255))throw new IllegalArgumentException("白色的 RGB 各通道应高于黑色，请核对取样");
    }
    /** Area averaging in linear light, not an average of gamma-encoded camera RGB. */
    public static int sample(int[] pixels,int width,int height,int cx,int cy,int radius){
        if(width<1||height<1||(long)width*height!=pixels.length||cx<0||cx>=width||cy<0||cy>=height||radius<0)
            throw new IllegalArgumentException("取样位置无效");
        int left=Math.max(0,cx-radius),right=(int)Math.min(width,(long)cx+radius+1);
        int top=Math.max(0,cy-radius),bottom=(int)Math.min(height,(long)cy+radius+1);double r=0,g=0,b=0;int count=0;
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int p=pixels[y*width+x];if((p>>>24)!=255)throw new IllegalArgumentException("请在不透明的屏幕色块内部取样");
            r+=linear((p>>>16)&255);g+=linear((p>>>8)&255);b+=linear(p&255);count++;
        }
        return 0xff000000|(encoded(r/count)<<16)|(encoded(g/count)<<8)|encoded(b/count);
    }
    /** Four solid quadrants, sent directly without image adjustments or quantization. */
    public static byte[] chart(int width,int height){
        if(width<2||height<2||(long)width*height>Integer.MAX_VALUE)throw new IllegalArgumentException("测试图尺寸错误");
        byte[] codes=new byte[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)codes[y*width+x]=(byte)((y<height/2?0:2)+(x<width/2?0:1));
        return codes;
    }
}
