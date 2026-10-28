package org.nfcresearch.qpolllab;

import android.os.*;
import android.os.Process;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** A separate, fixed-purpose Root service: never calls ReaderMode or a panel APDU. */
public final class RootHalBridge extends Binder {
    static final String INTERFACE="org.nfcresearch.qpolllab.ForegroundHal";
    private String apk;
    private final int appUid;
    private final Object lock=new Object();
    private final StringBuilder logs=new StringBuilder();
    private volatile boolean active,stream,inject;
    private volatile HalLease owner;
    private volatile RootNfcSupport liveHook;
    public RootHalBridge()throws Exception {
        if(Process.myUid()!=0&&Process.myUid()!=2000)throw new SecurityException("Shell/root required");
        Object pm=Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        ApplicationInfo info=(ApplicationInfo)Class.forName("android.content.pm.IPackageManager")
                .getMethod("getApplicationInfo",String.class,long.class,int.class).invoke(pm,"org.nfcresearch.qpolllab",0L,0);
        if(info==null)throw new IOException("App missing");apk=info.sourceDir;appUid=info.uid;
    }
    private RootHalBridge(String apk){this.apk=apk;appUid=-1;stream=true;}
    private void log(String line){synchronized(lock){
        if(stream){System.out.println(line);System.out.flush();}
        else {logs.append(line).append('\n');if(logs.length()>50000)logs.delete(0,logs.indexOf("\n",logs.length()-40000)+1);}
    }}
    private void control(int value){
        HalLease lease=owner;if(lease!=null)lease.command(value,SystemClock.elapsedRealtime());
        if(value!=HalLease.HEARTBEAT){RootNfcSupport hook=liveHook;if(hook!=null)try{hook.disableForForeground();}catch(IOException e){log("QP|RESTORE_FAILED|关闭前台 HAL 开关失败："+e);}}
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
        if(code==16777115){control(HalLease.CANCEL);new Thread(()->{long end=SystemClock.elapsedRealtime()+45000;while(active&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);System.exit(0);},"hal-destroy").start();return true;}
        if(code<1||code>4)return super.onTransact(code,data,reply,flags);
        if(Binder.getCallingUid()!=appUid)throw new SecurityException("Wrong caller");data.enforceInterface(INTERFACE);
        if(code==4){reply.writeNoException();reply.writeInt(Process.myUid());return true;}
        if(code==1){
            if(Process.myUid()!=0)throw new SecurityException("前台 HAL 兼容需要 Shizuku Root；Shell 权限不能注入 HAL");
            IBinder token=data.readStrongBinder();if(token==null)throw new IllegalArgumentException("Owner required");inject=data.readInt()!=0;
            synchronized(lock){if(active)throw new IllegalStateException("HAL 会话尚未结束");active=true;logs.setLength(0);owner=new HalLease(SystemClock.elapsedRealtime());}
            IBinder.DeathRecipient death=()->control(HalLease.CANCEL);
            try{token.linkToDeath(death,0);}catch(RemoteException e){active=false;throw e;}
            new Thread(()->{try{run();}catch(Exception e){log("QP|ERROR|Root HAL："+e);}finally{
                token.unlinkToDeath(death,0);log("QP|HAL_DONE|Root HAL 会话已结束");active=false;
            }},"foreground-root-hal").start();reply.writeNoException();return true;
        }
        if(code==2){synchronized(lock){control(HalLease.HEARTBEAT);reply.writeNoException();reply.writeString(logs.toString());logs.setLength(0);reply.writeInt(active?1:0);}return true;}
        control(data.readInt());reply.writeNoException();return true;
    }
    private FileChannel lockChannel(File file)throws Exception {
        if(!file.getCanonicalFile().equals(file))throw new IOException("Invalid NFC lock path");
        FileChannel result=FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{checked("chown","2000:2000",file.toString());checked("chcon","u:object_r:shell_data_file:s0",file.toString());checked("chmod","600",file.toString());return result;}
        catch(Exception e){result.close();throw e;}
    }
    private void run()throws Exception {
        if(Process.myUid()!=0)throw new SecurityException("HAL 管理进程未取得 Root");
        String current=InstalledApp.sessionPath(apk,appUid);
        if(!current.equals(apk))log("QP|APK_REFRESH|安装包路径已变化，已刷新 HAL 服务的 APK 路径");
        apk=current;log("QP|APK_SOURCE|本轮 HAL 资产来自当前 APK："+apk);
        log("QP|HAL_IDENTITY|Root 管理 UID=0；NFC 通信由前台 App 执行");
        try(FileChannel session=lockChannel(new File("/data/local/tmp/qpolllab-session.lock"));FileLock sessionLock=session.tryLock()){
            if(sessionLock==null)throw new IOException("另一 NFC 会话尚未结束");
            try(FileChannel worker=lockChannel(new File("/data/local/tmp/qpolllab-worker.lock"));FileLock workerLock=worker.tryLock()){
                if(workerLock==null)throw new IOException("上次 NFC worker 尚在运行");
                if(owner.disable(SystemClock.elapsedRealtime())){log("QP|HAL_DISABLED|任务已停止，未准备 HAL");return;}
                RootNfcSupport.clearStaleLease();
                try(RootNfcSupport hook=new RootNfcSupport(apk,this::log)){
                    if(inject)hook.prepare();
                    liveHook=hook;
                    if(!owner.disable(SystemClock.elapsedRealtime()))log("QP|HAL_READY|HAL 已准备，允许前台 App 开启 ReaderMode");
                    long deadline=SystemClock.elapsedRealtime()+335000;
                    while(!owner.disable(SystemClock.elapsedRealtime())&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
                    hook.disableForForeground();hook.beforeReaderRestore();
                    log("QP|HAL_DISABLED|已关闭 HAL 改写，允许释放前台 ReaderMode");
                    long releaseDeadline=SystemClock.elapsedRealtime()+20000;
                    while(!owner.released()&&SystemClock.elapsedRealtime()<releaseDeadline)SystemClock.sleep(50);
                    if(!owner.released())log("QP|RESTORE_FAILED|前台 ReaderMode 释放未确认，请关闭再开启 NFC");
                }finally{liveHook=null;}
            }
        }
    }
    private static void checked(String... command)throws Exception {
        java.lang.Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
        if(!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("HAL 锁准备超时");}
        if(p.exitValue()!=0)throw new IOException("HAL 锁权限准备失败："+command[0]);
    }
    static String launchScript(String apk,boolean inject){return "export CLASSPATH="+BackendPolicy.quote(apk)+"; exec /system/bin/app_process /system/bin org.nfcresearch.qpolllab.RootHalBridge "+BackendPolicy.quote(apk)+(inject?" inject":" lock")+" 2>&1";}
    public static void main(String[] args){
        int exit=1;
        try{
            if(args.length!=2||!new File(args[0]).isFile()||(!args[1].equals("inject")&&!args[1].equals("lock")))throw new IllegalArgumentException("Invalid HAL bridge");
            RootHalBridge bridge=new RootHalBridge(args[0]);bridge.inject=args[1].equals("inject");bridge.owner=new HalLease(SystemClock.elapsedRealtime());
            bridge.active=true;bridge.log("QP|HAL_PROCESS_READY|Root HAL 管理通道已启动");
            Thread input=new Thread(()->{try{int value;while((value=System.in.read())!=-1)bridge.control(value);}catch(IOException ignored){}finally{bridge.control(HalLease.CANCEL);}},"hal-owner");input.setDaemon(true);input.start();
            try{bridge.run();exit=0;}finally{bridge.log("QP|HAL_DONE|Root HAL 管理进程已结束");bridge.active=false;}
        }catch(Exception e){System.out.println("QP|ERROR|Root HAL："+e);System.out.flush();}
        System.exit(exit);
    }
}
