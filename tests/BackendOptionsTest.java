package org.nfcresearch.qpolllab;

public final class BackendOptionsTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static boolean available(int uid,int position,boolean arm64,String config){return BackendOptions.blockedReason(uid,position,arm64,config).isEmpty();}
    public static void main(String[] args){
        String matching="NXP SN220U\n来自系统配置",other="NXP PN553\n来自系统配置";
        for(int uid:new int[]{-1,0,2000}){
            check(!available(uid,0,true,matching),"Placeholder must not become an executable backend");
            check(available(uid,1,true,matching)&&available(uid,3,true,matching),"Unverified identities may configure standard or direct modes without claiming support");
            check(available(uid,2,true,matching)==(uid==0),"HAL selection requires Root");
            check(!available(uid,2,false,matching),"No HAL option without supported native architecture");
            check(!available(uid,2,true,other),"Known non-SN220 configuration rejected");
        }
        check(available(0,2,true,null)&&available(0,2,true,"未能识别"),"Unprivileged unreadable configuration is unknown; privileged runtime validation remains necessary");
        check(!available(-1,2,true,null),"Permission grant without a confirmed service identity cannot enable HAL");
        check(BackendOptions.retainedSelection(2000,2,true,matching,false)==0,"Root to Shell must clear HAL selection without substituting another backend");
        check(BackendOptions.retainedSelection(-1,2,true,matching,false)==0,"Service death must clear HAL selection when idle");
        check(BackendOptions.retainedSelection(0,2,true,other,false)==0,"Configuration change invalidates selection");
        check(BackendOptions.retainedSelection(2000,2,true,matching,true)==2,"Never mutate active session selection during connection loss");
        check(BackendOptions.retainedSelection(0,2,true,matching,false)==2,"Eligible Root selection retained");
        for(int position:new int[]{1,3})check(BackendOptions.retainedSelection(2000,position,true,matching,false)==position,"Do not change independent valid choices");
        check(BackendOptions.transport(0).isEmpty()&&BackendOptions.transport(1).equals("system")&&BackendOptions.transport(2).equals("sn220")&&BackendOptions.transport(3).equals("direct"),"Menu and worker transport mapping");
        for(int position=1;position<=3;position++){
            if(available(2000,position,true,matching))BackendPolicy.validate(2000,"refresh",BackendOptions.transport(position));
            if(available(0,position,true,matching))BackendPolicy.validate(0,"refresh",BackendOptions.transport(position));
        }
        try{BackendPolicy.validate(2000,"refresh","sn220");throw new AssertionError("Runtime Root gate bypassed");}catch(SecurityException expected){}
        System.out.println("BackendOptionsTest: PASS (Root/Shell/unknown identity, architecture/config limits, invalid selection clearing, session freeze, transport mapping and runtime gate)");
    }
}
