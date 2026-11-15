package org.nfcresearch.qpolllab;

public final class TestChartRasterTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int mask(byte[] codes){int mask=0;for(byte code:codes)mask|=1<<(code&3);return mask;}
    private static int[] chart(int width,int height){
        return TestChartPattern.createPixels(width,height);
    }
    private static void verify(byte[] codes,int[] source,int[] palette,String mode){
        TestChartPolicy.preserveExactEndpoints(source,codes);
        TestChartPolicy.requireVisible(codes,mode);
        check(codes[0]==0,"black endpoint changed: "+mode);
        check(source.length>10*552+10 && (source[10*552+10]&0x00ffffff)==0x00ffffff && codes[10*552+10]==1,"white endpoint changed: "+mode);
        check(Integer.bitCount(mask(codes))>=2,"chart collapsed to one colour: "+mode);
        int[] preview=TestChartPolicy.previewPixels(codes,palette);
        int first=preview[0],different=0;
        for(int color:preview)if(color!=first){different++;break;}
        check(different>0,"preview collapsed to a single display colour: "+mode);
        for(int color:preview)check((color>>>24)==255,"preview contains transparent pixels: "+mode);
    }
    public static void main(String[] args){
        int width=552,height=768;int[] source=chart(width,height),palette=CalibrationPalette.defaults();
        check(source.length==width*height,"chart source size");
        boolean hasYellow=false,hasRed=false,hasGreen=false,hasBlue=false;
        for(int pixel:source){
            check((pixel>>>24)==255,"chart source must be opaque");
            int rgb=pixel&0xffffff;
            if(rgb==0x00ffd000)hasYellow=true;
            if(rgb==0x00c80000)hasRed=true;
            if(rgb==0x0021834a)hasGreen=true;
            if(rgb==0x003366cc)hasBlue=true;
        }
        check(hasYellow&&hasRed&&hasGreen&&hasBlue,"raw chart must contain chromatic target anchors");
        for(ColorMixPreset preset:ColorMixPreset.values()){
            byte[] output=CalibratedRaster.quantize(source,width,height,true,1,palette,preset);
            verify(output,source,palette,"混色算法 / "+preset.name());
            TestChartPolicy.requireChromaticCodes(output,"混色算法 / "+preset.name());
            int[] tuned=source.clone();new ImageAdjustments(50,15,20).applyTo(tuned);
            byte[] adjusted=CalibratedRaster.quantize(tuned,width,height,true,1,palette,preset);
            check(!java.util.Arrays.equals(output,adjusted),"Mixing chart adjustment must affect output: "+preset.name());
        }
        for(int mode=0;mode<ReinkRaster.MODES.length;mode++){
            byte[] output=ReinkRaster.quantize(source,width,height,mode,palette);
            verify(output,source,palette,"出图方法 / "+ReinkRaster.MODES[mode]);
            int[] counts=TestChartPolicy.histogram(output);
            if(mode!=ReinkRaster.MODE_INK){
                check(counts[2]>0,"colour recipe lost yellow output: "+ReinkRaster.MODES[mode]);
                check(counts[3]>0,"colour recipe lost red output: "+ReinkRaster.MODES[mode]);
                TestChartPolicy.requireChromaticCodes(output,"出图方法 / "+ReinkRaster.MODES[mode]);
            }
            int[] tuned=source.clone();new ImageAdjustments(50,15,20).applyTo(tuned);
            byte[] adjusted=ReinkRaster.quantize(tuned,width,height,mode,palette);
            check(!java.util.Arrays.equals(output,adjusted),"Style chart adjustment must affect output: "+ReinkRaster.MODES[mode]);
        }
        boolean failed=false;
        try{TestChartPolicy.requireVisible(new byte[]{0,0,0},"故障模拟");}catch(IllegalStateException expected){failed=true;}
        check(failed,"single-colour chart must fail visibly rather than appear as a black preview");
        System.out.println("TestChartRasterTest: PASS (real 552x768 source fixture, chromatic anchor preservation, six mixing presets, four Reink recipes, endpoint preservation, output histograms and direct preview pixels)");
    }
}
