package org.nfcresearch.qpolllab;

import java.util.Arrays;

public final class ImageAdjustmentsTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static int edit(int color,int brightness,int temperature,int contrast){int[] pixel={color};new ImageAdjustments(brightness,temperature,contrast).applyTo(pixel);return pixel[0];}
    private static int red(int color){return(color>>>16)&255;}
    private static int blue(int color){return color&255;}
    private static void rejected(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
    public static void main(String[] args){
        int[] source={0xff000000,0xffffffff,0xff808080,0xfff5cd00,0xffc80000,0x40204080};
        int[] neutral=source.clone();ImageAdjustments.NONE.applyTo(neutral);check(Arrays.equals(neutral,source),"Zero settings must be byte-exact");
        check(red(edit(0xff646464,50,0,0))==138,"Brightness +50 should double linear-light exposure");
        check(red(edit(0xff646464,-50,0,0))==71,"Brightness -50 should halve linear-light exposure");
        int warm=edit(0xff808080,0,100,0),cool=edit(0xff808080,0,-100,0);
        check(red(warm)>128&&blue(warm)<128,"Positive temperature must warm the image");
        check(red(cool)<128&&blue(cool)>128,"Negative temperature must cool the image");
        check(red(edit(0xff404040,0,0,100))<64&&red(edit(0xffc8c8c8,0,0,100))>200,"Increasing contrast must separate dark/light tones");
        check(red(edit(0xff404040,0,0,-100))>64&&red(edit(0xffc8c8c8,0,0,-100))<200,"Reducing contrast must bring tones together");
        check((edit(0x40204080,100,100,100)>>>24)==0x40,"Opacity must be retained");
        for(int brightness:new int[]{-100,0,100})for(int temperature:new int[]{-100,0,100})for(int contrast:new int[]{-100,0,100}){
            int[] gradient=new int[256];for(int i=0;i<256;i++)gradient[i]=0xff000000|(i<<16)|(i<<8)|i;
            new ImageAdjustments(brightness,temperature,contrast).applyTo(gradient);
            for(int channel:new int[]{0,8,16})for(int i=1;i<256;i++)check(((gradient[i]>>>channel)&255)>=((gradient[i-1]>>>channel)&255),"Tone curve must remain monotonic without channel wraparound");
        }
        // Editing is upstream of the same raster/packing path used for previews and sending.
        int[] midtones=new int[552*768];Arrays.fill(midtones,0xff646464);
        byte[] dark=ImageRaster.quantize(midtones,552,768,true,128,false,1);
        new ImageAdjustments(50,0,0).applyTo(midtones);
        byte[] light=ImageRaster.quantize(midtones,552,768,true,128,false,1);
        check(dark[0]==0&&light[0]==1,"Brightness must affect binary output too");
        for(int[] palette:new int[][]{CalibrationPalette.defaults(),{0xff202020,0xffc8c8c8,0xffb8a030,0xffa83030}}){
            byte[] codes=CalibratedRaster.quantize(midtones,552,768,true,1,palette);
            for(PanelType type:PanelType.values()){
                int[] preview=type.preview(type.pack(codes),palette);
                for(int i=0;i<codes.length;i++)check(preview[i]==palette[codes[i]],"Adjusted preview and packed output must agree");
            }
        }
        rejected(()->new ImageAdjustments(101,0,0));rejected(()->new ImageAdjustments(0,-101,0));rejected(()->new ImageAdjustments(0,0,101));
        System.out.println("ImageAdjustmentsTest: PASS (identity, exposure, warm/cool direction, contrast, alpha, monotonic bounds, binary output, calibrated A0/A1 preview)");
    }
}
