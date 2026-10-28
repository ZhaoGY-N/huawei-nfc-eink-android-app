package org.nfcresearch.qpolllab;

public final class NxpHalPolicyTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(String model:new String[]{"SN220","SN220U","SN100U"}){
            String value=NxpHalPolicy.firmwareConfig("# NXP_FW_NAME=\"libpn553_fw.so\"\n NXP_FW_NAME = \"lib"+model.toLowerCase(java.util.Locale.ROOT)+"_fw.so\"\n","/odm/etc/libnfc-nci.conf");
            check(NxpHalPolicy.supported(value),"Supported firmware: "+model);
            check(value.contains("/odm/etc/libnfc-nci.conf"),"Evidence source preserved");
            for(int uid:new int[]{0,2000,-1})check(BackendOptions.blockedReason(uid,2,true,value).isEmpty()==(uid==0),"HAL always requires Root");
            check(!BackendOptions.blockedReason(0,2,false,value).isEmpty(),"HAL always requires arm64");
        }
        for(String model:new String[]{"NXP PN553","NXP SN110","ST54L","",null})check(!NxpHalPolicy.supported(model),"No unrelated or unknown chip admitted at runtime");
        check(NxpHalPolicy.firmwareConfig("#NXP_FW_NAME=\"libsn100u_fw.so\"","test")==null,"Commented firmware is not evidence");
        check(BackendOptions.transport(2).equals("sn220"),"Persisted transport identity remains stable");
        for(int i:new int[]{1,3})check(BackendOptions.blockedReason(2000,i,false,"NXP SN100U").isEmpty(),"Other discovery modes are independent");
        System.out.println("NxpHalPolicyTest: PASS (SN220/SN220U preserved, SN100U admitted, Root/arm64 retained, unrelated chips rejected, config parsing)");
    }
}
