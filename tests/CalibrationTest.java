package org.nfcresearch.qpolllab;

import java.util.Arrays;

public final class CalibrationTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void rejected(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
    public static void main(String[] args){
        // Equal areas of physical black and white average to sRGB ~188 in linear light.
        int mixed=CalibrationPalette.sample(new int[]{0xff000000,0xffffffff},2,1,0,0,1);
        check(mixed==0xffbcbcbc,"Area sampling must average linear light");
        int[] photo={0xff102030,0xff405060,0xff708090,0xffa0b0c0};
        check(CalibrationPalette.sample(photo,2,2,1,1,0)==photo[3],"Point sample coordinates");
        check(CalibrationPalette.sample(photo,2,2,0,0,100)==CalibrationPalette.sample(photo,2,2,1,1,100),"Edge-clipped sampling");
        rejected(()->CalibrationPalette.sample(photo,2,2,-1,0,2));
        rejected(()->CalibrationPalette.sample(new int[]{0},1,1,0,0,0));
        rejected(()->CalibrationPalette.validate(new int[]{0xffaaaaaa,0xff333333,0xffeeee00,0xffbb0000}));
        rejected(()->CalibrationPalette.validate(new int[]{0xff000000,0xffffffff,0xff000000,0xffbb0000}));
        rejected(()->CalibrationPalette.validate(new int[]{0xff000080,0xffffff40,0xffffff00,0xffbb0000}));
        int[] measured={0xff202020,0xffc8c8c8,0xffb8a030,0xffa83030};CalibrationPalette.validate(measured);
        int[] saved=measured.clone();int w=552,h=768;
        byte[] chart=CalibrationPalette.chart(w,h);
        check(chart[0]==0&&chart[w-1]==1&&chart[(h-1)*w]==2&&chart[w*h-1]==3,"Chart orientation");
        for(PanelType type:PanelType.values()){
            byte[] packed=type.pack(chart);int[] actual=type.preview(packed,measured);
            check(packed.length==105984,"Wire payload length");
            for(int i=0;i<actual.length;i++)check(actual[i]==measured[chart[i]],"Calibrated preview must decode the actual panel layout");
        }
        int[] gray=new int[w*h];Arrays.fill(gray,0xffbcbcbc);
        byte[] half=CalibratedRaster.quantize(gray,w,h,true,1,measured);int white=0;
        for(byte code:half){check(code==0||code==1,"Neutral gray must not gain yellow/red speckles");if(code==1)white++;}
        check(Math.abs(white/(double)gray.length-0.5)<0.02,"Exposure-normalized midpoint should preserve a half-tone");
        int[] source=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)source[y*w+x]=0xff000000|((x*255/(w-1))<<16)|((y*255/(h-1))<<8)|((x+y)%256);
        source[0]=0xffffffff;source[1]=0xff000000;int[] original=source.clone();int changes=0;
        byte[] baseline=CalibratedRaster.quantize(source,w,h,false,1,CalibrationPalette.defaults());
        for(int block:new int[]{1,2,4,8,16})for(boolean dither:new boolean[]{false,true}){
            byte[] codes=CalibratedRaster.quantize(source,w,h,dither,block,measured);
            for(byte code:codes)check(code>=0&&code<4,"Illegal wire code");
            if(block==1){check(codes[0]==1&&codes[1]==0,"Pure white/black protection");if(!dither)for(int i=0;i<codes.length;i++)if(codes[i]!=baseline[i])changes++;}
            else for(int y=0;y<h;y++)for(int x=0;x<w;x++)check(codes[y*w+x]==codes[(y/block*block)*w+x/block*block],"Block mode must retain one code per block");
        }
        check(changes>1000,"Measured profile must affect quantization, not only preview");
        check(Arrays.equals(measured,saved)&&Arrays.equals(source,original),"Input mutation");
        rejected(()->CalibratedRaster.quantize(source,w,h,true,3,measured));
        rejected(()->ImageRaster.preview(new byte[2],2,4,new int[]{0,1}));
        System.out.println("CalibrationTest: PASS (linear photo sampling, validation, exposure normalization, neutral/white protection, all block modes, A0/A1 packed preview)");
    }
}
