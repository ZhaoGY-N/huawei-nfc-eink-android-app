package org.nfcresearch.qpolllab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fixed privilege paths. UID 0 NFC calls require an explicit legacy selection. */
public final class BackendPolicy {
    public static final int POWER_SECONDS = 300;
    private BackendPolicy() {}
    public static boolean needsQPoll(String mode) {
        return mode.equals("qtag") || mode.equals("identity") || mode.equals("refresh") || mode.equals("power");
    }
    public static boolean runsPanelProtocol(String mode) {
        return mode.equals("identity") || mode.equals("refresh");
    }
    public static boolean requiresRoot(String transport){return transport.equals("sn220");}
    public static void validateDuration(String mode, int seconds) {
        if(seconds<1 || seconds>(mode.equals("power")?POWER_SECONDS:60))
            throw new IllegalArgumentException("时长超出当前模式允许范围");
    }
    public static void validate(int uid, String mode, String transport) {
        if(uid!=0 && uid!=2000)throw new SecurityException("请通过 Shizuku Root 或 Shell 启动后端");
        if(!Arrays.asList("inspect","standard","qtag","identity","refresh","power").contains(mode))
            throw new IllegalArgumentException("Unknown NFC mode");
        if(!transport.equals("system") && !transport.equals("sn220") && !transport.equals("direct"))throw new IllegalArgumentException("Unknown discovery backend");
        if(requiresRoot(transport) && uid!=0 && needsQPoll(mode))
            throw new SecurityException("NXP HAL 兼容层需要 Root（Shizuku Root 或 Root 直连）；当前是 Shell 权限");
    }
    public static String quote(String value) {return "'"+value.replace("'","'\\''")+"'";}
    public static List<String> workerCommand(int uid, String apk, String mode, int seconds,
            String stop, String lock, String job, String transport) {
        return workerCommand(uid,apk,mode,seconds,stop,lock,job,transport,false);
    }
    public static int workerUid(int launcherUid, boolean legacyRoot) {
        if(launcherUid!=0&&launcherUid!=2000)throw new SecurityException("Shell/root required");
        if(legacyRoot&&launcherUid!=0)throw new SecurityException("旧版 Root 直连需要 UID 0");
        return legacyRoot?0:2000;
    }
    public static void validateWorker(int uid, String domain, boolean legacyRoot) {
        if(legacyRoot){if(uid!=0)throw new SecurityException("旧版 Root 模式未取得 UID 0");}
        else if(uid!=2000||!"u:r:shell:s0".equals(domain))
            throw new SecurityException("当前模式必须以 UID 2000 和 shell SELinux 域运行，不能回退到 Root");
    }
    public static String launchScript(String apk, boolean legacyRoot) {
        return "export CLASSPATH="+quote(apk)+"; exec /system/bin/app_process /system/bin "
                +"org.nfcresearch.qpolllab.ShellBridge "+(legacyRoot?"legacy-root":"process")+" "+quote(apk)+" 2>&1";
    }
    public static List<String> workerCommand(int uid, String apk, String mode, int seconds,
            String stop, String lock, String job, String transport, boolean legacyRoot) {
        validate(uid,mode,transport);
        validateDuration(mode,seconds);
        int workerUid=workerUid(uid,legacyRoot);
        List<String> args=new ArrayList<>(Arrays.asList("/system/bin/app_process","/system/bin",
                "org.nfcresearch.qpolllab.QPollWorker",mode,""+seconds,stop,lock,job,transport));
        if(legacyRoot)args.add("legacy-root");
        if(uid==2000||workerUid==0)return args;
        StringBuilder script=new StringBuilder("export CLASSPATH=").append(quote(apk)).append("; exec");
        for(String arg:args)script.append(' ').append(quote(arg));
        // Both the Linux identity and SELinux domain must change. Never fall back to UID 0.
        return Arrays.asList("su","2000","-c","exec /system/bin/runcon u:r:shell:s0 /system/bin/sh -c "+quote(script.toString()));
    }
}
