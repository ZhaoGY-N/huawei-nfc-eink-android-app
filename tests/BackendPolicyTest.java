package org.nfcresearch.qpolllab;

import java.util.List;
public final class BackendPolicyTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void rejected(Runnable action){try{action.run();throw new AssertionError("accepted invalid backend");}catch(SecurityException|IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        for(int uid:new int[]{0,2000})for(String mode:new String[]{"inspect","standard","qtag","identity","refresh","power"})BackendPolicy.validate(uid,mode,"system");
        BackendPolicy.validate(0,"refresh","sn220");
        BackendPolicy.validate(0,"power","sn220");
        rejected(()->BackendPolicy.validate(2000,"power","sn220"));
        check(BackendPolicy.needsQPoll("power")&&!BackendPolicy.runsPanelProtocol("power"));
        check(BackendPolicy.runsPanelProtocol("identity")&&BackendPolicy.runsPanelProtocol("refresh"));
        for(String mode:new String[]{"inspect","standard","qtag"})check(!BackendPolicy.runsPanelProtocol(mode));
        List<String> power=BackendPolicy.workerCommand(2000,"apk","power",300,"s","l","j","system");
        check(power.get(3).equals("power")&&power.get(4).equals("300"));
        rejected(()->BackendPolicy.workerCommand(2000,"apk","power",301,"s","l","j","system"));
        rejected(()->BackendPolicy.workerCommand(2000,"apk","power",0,"s","l","j","system"));
        rejected(()->BackendPolicy.workerCommand(2000,"apk","identity",61,"s","l","j","system"));
        rejected(()->BackendPolicy.workerCommand(2000,"apk","refresh",300,"s","l","j","system"));
        rejected(()->BackendPolicy.validate(2000,"refresh","sn220"));
        rejected(()->BackendPolicy.validate(10001,"identity","system"));
        rejected(()->BackendPolicy.validate(0,"unknown","system"));
        rejected(()->BackendPolicy.validate(0,"qtag","auto"));
        List<String> shell=BackendPolicy.workerCommand(2000,"apk","identity",30,"stop","lock","job","system");
        check(shell.get(0).equals("/system/bin/app_process")&&shell.size()==9);
        List<String> root=BackendPolicy.workerCommand(0,"a'b $(touch SHOULD_NOT_EXIST).apk","refresh",30,"stop","lock","job","sn220");
        check(root.get(0).equals("su")&&root.get(1).equals("2000")&&root.get(3).contains("runcon u:r:shell:s0"));
        List<String> legacy=BackendPolicy.workerCommand(0,"apk","refresh",30,"stop","lock","job","system",true);
        check(legacy.get(0).equals("/system/bin/app_process")&&legacy.size()==10&&legacy.get(9).equals("legacy-root"));
        check(BackendPolicy.workerUid(0,true)==0&&BackendPolicy.workerUid(0,false)==2000&&BackendPolicy.workerUid(2000,false)==2000);
        rejected(()->BackendPolicy.workerUid(2000,true));
        rejected(()->BackendPolicy.workerUid(10001,false));
        rejected(()->BackendPolicy.workerCommand(2000,"apk","refresh",10,"s","l","j","system",true));
        BackendPolicy.validateWorker(0,"u:r:su:s0",true);
        BackendPolicy.validateWorker(2000,"u:r:shell:s0",false);
        rejected(()->BackendPolicy.validateWorker(0,"u:r:su:s0",false));
        rejected(()->BackendPolicy.validateWorker(2000,"u:r:su:s0",false));
        rejected(()->BackendPolicy.validateWorker(2000,"u:r:shell:s0",true));
        for(String mode:new String[]{"qtag","identity","refresh","power"}){
            BackendPolicy.validate(2000,mode,"direct");BackendPolicy.validate(0,mode,"direct");
        }
        check(BackendPolicy.launchScript("a'b $(touch SHOULD_NOT_EXIST).apk",true).contains("ShellBridge legacy-root"));
        check(BackendPolicy.launchScript("apk",false).contains("ShellBridge process"));
        rejected(()->BackendPolicy.workerCommand(0,"apk","qtag",0,"s","l","j","system"));
        // Exercise the actual shell parser with quotes, whitespace and command substitutions.
        String value="a'b $(touch SHOULD_NOT_EXIST)\nspace";
        Process process=new ProcessBuilder("/bin/sh","-c","printf %s "+BackendPolicy.quote(value)).start();
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int b;while((b=process.getInputStream().read())!=-1)out.write(b);
        check(process.waitFor()==0&&new String(out.toByteArray(),"UTF-8").equals(value));
        check(!new java.io.File("SHOULD_NOT_EXIST").exists());
        System.out.println("Backend identity, privilege and shell argument tests passed");
    }
}
