package org.nfcresearch.qpolllab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Privilege decisions shared by the UI/service; workers always use shell UID. */
public final class BackendPolicy {
    private BackendPolicy() {}
    public static boolean needsQPoll(String mode) {
        return mode.equals("qtag") || mode.equals("identity") || mode.equals("refresh");
    }
    public static void validate(int uid, String mode, String transport) {
        if(uid!=0 && uid!=2000)throw new SecurityException("请通过 Shizuku Root 或 Shell 启动后端");
        if(!Arrays.asList("inspect","standard","qtag","identity","refresh").contains(mode))
            throw new IllegalArgumentException("Unknown NFC mode");
        if(!transport.equals("system") && !transport.equals("sn220"))throw new IllegalArgumentException("Unknown discovery backend");
        if(transport.equals("sn220") && uid!=0 && needsQPoll(mode))
            throw new SecurityException("SN220 兼容层需要 Shizuku 以 Root 启动；当前是 Shell 权限");
    }
    public static String quote(String value) {return "'"+value.replace("'","'\\''")+"'";}
    public static List<String> workerCommand(int uid, String apk, String mode, int seconds,
            String stop, String lock, String job, String transport) {
        validate(uid,mode,transport);
        if(seconds<1 || seconds>60)throw new IllegalArgumentException("Invalid duration");
        List<String> args=new ArrayList<>(Arrays.asList("/system/bin/app_process","/system/bin",
                "org.nfcresearch.qpolllab.QPollWorker",mode,""+seconds,stop,lock,job,transport));
        if(uid==2000)return args;
        StringBuilder script=new StringBuilder("export CLASSPATH=").append(quote(apk)).append("; exec");
        for(String arg:args)script.append(' ').append(quote(arg));
        // Both the Linux identity and SELinux domain must change. Never fall back to UID 0.
        return Arrays.asList("su","2000","-c","exec /system/bin/runcon u:r:shell:s0 /system/bin/sh -c "+quote(script.toString()));
    }
}
