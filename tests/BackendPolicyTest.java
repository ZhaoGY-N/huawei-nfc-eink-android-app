package org.nfcresearch.qpolllab;

import java.util.List;
public final class BackendPolicyTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void rejected(Runnable action){try{action.run();throw new AssertionError("accepted invalid backend");}catch(SecurityException|IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        for(int uid:new int[]{0,2000})for(String mode:new String[]{"inspect","standard","qtag","identity","refresh"})BackendPolicy.validate(uid,mode,"system");
        BackendPolicy.validate(0,"refresh","sn220");
        rejected(()->BackendPolicy.validate(2000,"refresh","sn220"));
        rejected(()->BackendPolicy.validate(10001,"identity","system"));
        rejected(()->BackendPolicy.validate(0,"unknown","system"));
        rejected(()->BackendPolicy.validate(0,"qtag","auto"));
        List<String> shell=BackendPolicy.workerCommand(2000,"apk","identity",30,"stop","lock","job","system");
        check(shell.get(0).equals("/system/bin/app_process")&&shell.size()==9);
        List<String> root=BackendPolicy.workerCommand(0,"a'b $(touch SHOULD_NOT_EXIST).apk","refresh",30,"stop","lock","job","sn220");
        check(root.get(0).equals("su")&&root.get(1).equals("2000")&&root.get(3).contains("runcon u:r:shell:s0"));
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
