package org.nfcresearch.qpolllab;

import java.util.Arrays;

public final class TestImageModeTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        int[] measured={0xff202020,0xffc8c8c8,0xffb8a030,0xffa83030};
        for(TestImageMode mode:TestImageMode.values()){
            check(mode.calibrationPalette(measured)==(mode==TestImageMode.NONE?measured:null),"Only ordinary photos may use calibration: "+mode);
            check(mode.calibrationPalette(null)==null,"Disabled calibration");
        }
        TestImageMode[] solids={TestImageMode.BLACK,TestImageMode.WHITE,TestImageMode.YELLOW,TestImageMode.RED};
        for(int color=0;color<4;color++){
            byte[] codes=solids[color].fixedCodes(552,768);
            for(byte code:codes)check(code==color,"Solid source contains another color");
            for(PanelType type:PanelType.values()){
                byte[] packed=type.pack(codes);check(packed.length==105984,"Full-frame payload");
                for(int pixel:type.preview(packed))check(pixel==ImageRaster.color(color),"Solid transmitted/preview color mismatch: "+type);
            }
        }
        check(Arrays.equals(TestImageMode.CALIBRATION.fixedCodes(552,768),CalibrationPalette.chart(552,768)),"Calibration chart retains fixed codes");
        check(TestImageMode.TONES.fixedCodes(552,768)==null&&!TestImageMode.TONES.fixedRaster(),"Tone test retains adjustable quantization");
        check(TestImageMode.FOUR_COLOR.fixedCodes(552,768)==null,"Four-color test retains editing");
        System.out.println("TestImageModeTest: PASS (all test modes bypass calibration, four solid colors across A0/A1/A0X, chart and tone policy)");
    }
}
