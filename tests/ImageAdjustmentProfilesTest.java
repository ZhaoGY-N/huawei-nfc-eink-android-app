package org.nfcresearch.qpolllab;

import java.util.HashSet;
import java.util.Set;

public final class ImageAdjustmentProfilesTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        String[] bases={"imageBrightness","imageTemperature","imageContrast"};
        for(String base:bases){
            check(ImageAdjustmentProfiles.key(base,0).equals(base),"Original pipeline must preserve legacy preference key: "+base);
            Set<String> keys=new HashSet<>();
            for(int profile=0;profile<ImageAdjustmentProfiles.STYLE_COUNT;profile++)
                check(keys.add(ImageAdjustmentProfiles.key(base,profile)),"Adjustment profile keys must be unique for "+base);
        }
        check(ImageAdjustmentProfiles.name(0).equals("原项目算法"),"Original profile label");
        check(ImageAdjustmentProfiles.name(1).equals("鎏金")&&ImageAdjustmentProfiles.name(2).equals("质感")
                &&ImageAdjustmentProfiles.name(3).equals("色阶")&&ImageAdjustmentProfiles.name(4).equals("水墨"),"Recipe profile labels");
        check(ImageAdjustmentProfiles.normalize(-1)==0&&ImageAdjustmentProfiles.normalize(5)==0,"Invalid profile falls back safely");
        System.out.println("ImageAdjustmentProfilesTest: PASS (legacy key migration, unique per-method settings, labels and bounds)");
    }
}
