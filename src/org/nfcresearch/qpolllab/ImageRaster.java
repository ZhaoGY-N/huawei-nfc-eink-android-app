package org.nfcresearch.qpolllab;

/** Quantization and preview decoding share the panel's four code values. */
public final class ImageRaster {
    private static final int[] COLORS=CalibrationPalette.defaults();
    public static int color(int code){if(code<0||code>3)throw new IllegalArgumentException("颜色码错误");return COLORS[code];}
    public static byte[] quantize(int[] pixels,int width,int height,boolean binary,int threshold,boolean dither,int block) {
        if(width<=0||height<=0||(long)width*height!=pixels.length||threshold<0||threshold>255)
            throw new IllegalArgumentException("图片参数错误");
        if(block>1) {
            // Binary threshold must be applied after averaging, not to four-color codes.
            if(!binary)return ImageBlocks.quantize(pixels,width,height,block,COLORS);
            if(block!=2&&block!=4&&block!=8&&block!=16)throw new IllegalArgumentException("色块参数错误");
            byte[] out=new byte[pixels.length];
            for(int y=0;y<height;y+=block)for(int x=0;x<width;x+=block){
                int right=Math.min(width,x+block),bottom=Math.min(height,y+block);float sum=0;
                for(int yy=y;yy<bottom;yy++)for(int xx=x;xx<right;xx++)sum+=luma(pixels[yy*width+xx]);
                byte code=(byte)(sum/((right-x)*(bottom-y))>=threshold?1:0);
                for(int yy=y;yy<bottom;yy++)java.util.Arrays.fill(out,yy*width+x,yy*width+right,code);
            }
            return out;
        }
        if(block!=1)throw new IllegalArgumentException("色块参数错误");
        byte[] codes=new byte[pixels.length];
        float[][] current=new float[3][width+2],next=new float[3][width+2];
        for(int y=0;y<height;y++) {
            for(int x=0;x<width;x++) {
                int index=y*width+x,p=pixels[index],best=0;
                if(binary) {
                    float value=clamp(luma(p)+(dither?current[0][x+1]:0));best=value>=threshold?1:0;
                    if(dither)spread(current[0],next[0],x,value-(best==1?255:0));
                } else {
                    float r=clamp(((p>>>16)&255)+(dither?current[0][x+1]:0));
                    float g=clamp(((p>>>8)&255)+(dither?current[1][x+1]:0));
                    float b=clamp((p&255)+(dither?current[2][x+1]:0));
                    float score=Float.MAX_VALUE;
                    for(int k=0;k<4;k++){int c=COLORS[k];float dr=r-((c>>>16)&255),dg=g-((c>>>8)&255),db=b-(c&255),s=dr*dr+dg*dg+db*db;if(s<score){score=s;best=k;}}
                    if(dither){int c=COLORS[best];spread(current[0],next[0],x,r-((c>>>16)&255));spread(current[1],next[1],x,g-((c>>>8)&255));spread(current[2],next[2],x,b-(c&255));}
                }
                codes[index]=(byte)best;
            }
            float[][] old=current;current=next;next=old;for(float[] channel:next)java.util.Arrays.fill(channel,0);
        }
        return codes;
    }
    private static float luma(int p){return (299*((p>>>16)&255)+587*((p>>>8)&255)+114*(p&255))/1000f;}
    private static float clamp(float value){return Math.max(0,Math.min(255,value));}
    private static void spread(float[] current,float[] next,int x,float error){current[x+2]+=error*7/16;next[x]+=error*3/16;next[x+1]+=error*5/16;next[x+2]+=error/16;}
    public static int[] preview(byte[] packed,int width,int height) {
        return preview(packed,width,height,COLORS);
    }
    public static int[] preview(byte[] packed,int width,int height,int[] palette) {
        CalibrationPalette.validate(palette);
        if(width<=0||height<=0||height%4!=0||(long)width*height/4!=packed.length)throw new IllegalArgumentException("预览尺寸错误");
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int fromBottom=height-1-y,index=(width-1-x)*(height/4)+fromBottom/4;
            pixels[y*width+x]=palette[((packed[index]&255)>>(6-2*(fromBottom%4)))&3];
        }
        return pixels;
    }
}
