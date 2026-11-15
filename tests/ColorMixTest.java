package org.nfcresearch.qpolllab;

import java.util.Arrays;

public final class ColorMixTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static int count(byte[] codes,int wanted){int total=0;for(byte code:codes)if(code==wanted)total++;return total;}
    private static void valid(byte[] codes){for(byte code:codes)check(code>=0&&code<4,"Illegal panel colour code: "+code);}
    public static void main(String[] args){
        int[] palette={0xff202020,0xffc8c8c8,0xffb8a030,0xffa83030};
        int width=96,height=64;int[] source=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            if(y<height/2){int gray=80+x*96/(width-1);source[y*width+x]=0xff000000|(gray<<16)|(gray<<8)|gray;}
            else source[y*width+x]=0xff000000|(0xf0<<16)|(0x78<<8)|0x20;
        }
        int[] original=source.clone();
        byte[] legacyA=CalibratedRaster.quantize(source,width,height,true,1,palette);
        byte[] legacyB=CalibratedRaster.quantize(source,width,height,true,1,palette,ColorMixPreset.LEGACY_FS);
        check(Arrays.equals(legacyA,legacyB),"Legacy overload must remain byte-for-byte compatible");
        int[][] outputs=new int[ColorMixPreset.values().length][];
        for(ColorMixPreset preset:ColorMixPreset.values()){
            byte[] result=CalibratedRaster.quantize(source,width,height,true,1,palette,preset);valid(result);
            outputs[preset.ordinal()]=new int[]{count(result,0),count(result,1),count(result,2),count(result,3)};
            check(Arrays.equals(source,original),"Quantizer mutated source for "+preset);
        }
        // Neutral rows must remain a black/white mixture, never acquire coloured speckles.
        for(ColorMixPreset preset:new ColorMixPreset[]{ColorMixPreset.BARYCENTRIC_FS,ColorMixPreset.BARYCENTRIC_BAYER,ColorMixPreset.BARYCENTRIC_JJN}){
            byte[] result=CalibratedRaster.quantize(source,width,height,true,1,palette,preset);
            for(int y=0;y<height/2;y++)for(int x=0;x<width;x++)check(result[y*width+x]==0||result[y*width+x]==1,"Neutral ramp gained chromatic pixel in "+preset);
        }
        // All dithering presets should preserve the black/white endpoints exactly.
        int[] endpoints={0xff000000,0xffffffff};
        for(ColorMixPreset preset:ColorMixPreset.values()){
            byte[] result=CalibratedRaster.quantize(endpoints,2,1,true,1,palette,preset);
            check(result[0]==0&&result[1]==1,"Endpoint preservation: "+preset);
        }
        // Ordered dithering is deterministic and the proportional method creates multi-colour coverage.
        byte[] bayer1=CalibratedRaster.quantize(source,width,height,true,1,palette,ColorMixPreset.BARYCENTRIC_BAYER);
        byte[] bayer2=CalibratedRaster.quantize(source,width,height,true,1,palette,ColorMixPreset.BARYCENTRIC_BAYER);
        check(Arrays.equals(bayer1,bayer2),"Bayer chart must be deterministic");
        int chromatic=width*(height/2);int colored=count(bayer1,2)+count(bayer1,3);
        check(colored==chromatic&&count(bayer1,2)>0&&count(bayer1,3)>0,"Orange patch should be represented by both yellow and red coverage");
        byte[] noDither=CalibratedRaster.quantize(source,width,height,false,1,palette,ColorMixPreset.BARYCENTRIC_NONE);valid(noDither);
        // The JJN implementation exercises two future rows and both scan directions.
        byte[] jjn=CalibratedRaster.quantize(source,width,height,true,1,palette,ColorMixPreset.BARYCENTRIC_JJN);valid(jjn);
        System.out.println("ColorMixTest: PASS (legacy compatibility, six presets, palette convex coverage, neutral protection, endpoints, Bayer determinism and JJN bounds)");
    }
}
