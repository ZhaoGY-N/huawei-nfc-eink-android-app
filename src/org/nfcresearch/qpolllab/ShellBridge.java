package org.nfcresearch.qpolllab;
import android.os.*;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Shizuku service with fixed operations and bounded Binder messages. */
public final class ShellBridge extends Binder {
 private final int appUid; private final String apk;
 private final Object lock=new Object();private final StringBuilder logs=new StringBuilder();
 private volatile boolean active,cancelled;private volatile File stop;
 public ShellBridge()throws Exception {
  if(android.os.Process.myUid()!=0&&android.os.Process.myUid()!=2000)throw new SecurityException("Shell/root required");
  Object pm=Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
  ApplicationInfo info=(ApplicationInfo)Class.forName("android.content.pm.IPackageManager").getMethod("getApplicationInfo",String.class,long.class,int.class).invoke(pm,"org.nfcresearch.qpolllab",0L,0);
  if(info==null)throw new IllegalStateException("App missing");appUid=info.uid;apk=info.sourceDir;
 }
 private void log(String line){synchronized(lock){logs.append(line).append('\n');if(logs.length()>50000)logs.delete(0,logs.indexOf("\n",logs.length()-40000)+1);}}
 private void cancel(){cancelled=true;File file=stop;if(file!=null)try{file.createNewFile();}catch(Exception ignored){}}
 @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
  if(code==16777115){cancel();new Thread(()->{long deadline=SystemClock.elapsedRealtime()+330000;while(active&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);System.exit(0);},"nfc-destroy").start();return true;}
  if(code<1||code>4)return super.onTransact(code,data,reply,flags);
  if(Binder.getCallingUid()!=appUid)throw new SecurityException("Wrong caller");data.enforceInterface("org.nfcresearch.qpolllab.NfcSession");
  if(code==4){reply.writeNoException();reply.writeInt(android.os.Process.myUid());return true;}
  if(code==1){String mode=data.readString();int seconds=data.readInt();String json=data.readString();byte[] image=data.createByteArray();IBinder owner=data.readStrongBinder();
   if(mode==null||!mode.matches("inspect|standard|qtag|identity|refresh")||seconds<1||seconds>60||json==null||json.length()>8192||image==null||image.length>105984||owner==null)throw new IllegalArgumentException("Invalid NFC session");
   synchronized(lock){if(active)throw new IllegalStateException("另一会话尚未结束");active=true;cancelled=false;logs.setLength(0);}
   IBinder.DeathRecipient death=this::cancel;try{owner.linkToDeath(death,0);}catch(RemoteException e){active=false;throw e;}
   new Thread(()->{try{run(mode,seconds,json,image);}catch(Exception e){log("QP|ERROR|"+e);}finally{owner.unlinkToDeath(death,0);synchronized(lock){logs.append("QP|BRIDGE_END|后端会话结束\n");active=false;}}},"nfc-session").start();reply.writeNoException();return true;
  }
  if(code==2){synchronized(lock){reply.writeNoException();reply.writeString(logs.toString());logs.setLength(0);reply.writeInt(active?1:0);}return true;}
  cancel();reply.writeNoException();return true;
 }
 private void run(String mode,int seconds,String json,byte[] bytes)throws Exception {
  File lease=new File("/data/local/tmp/qpolllab-session.lock");
  if(!lease.getCanonicalFile().equals(lease))throw new IOException("Invalid session lock path");
  // Shared across bridge versions and Root/Shell services, including HAL cleanup.
  try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(lease.toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
   if(android.os.Process.myUid()==0){checked("chown","2000:2000",lease.toString());checked("chcon","u:object_r:shell_data_file:s0",lease.toString());}
   checked("chmod","600",lease.toString());
   try(java.nio.channels.FileLock session=channel.tryLock()){
    if(session==null)throw new IOException("另一 NFC 会话尚未结束");
    File workerLease=new File("/data/local/tmp/qpolllab-worker.lock");
    if(!workerLease.getCanonicalFile().equals(workerLease))throw new IOException("Invalid worker lock path");
    try(java.nio.channels.FileChannel workerChannel=java.nio.channels.FileChannel.open(workerLease.toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
     if(android.os.Process.myUid()==0){checked("chown","2000:2000",workerLease.toString());checked("chcon","u:object_r:shell_data_file:s0",workerLease.toString());}
     checked("chmod","600",workerLease.toString());
     // A worker may outlive a crashed bridge. Never change its HAL lease or start another session.
     try(java.nio.channels.FileLock orphan=workerChannel.tryLock()){
      if(orphan==null)throw new IOException("上次 NFC worker 尚在运行，请等待会话结束");
     }
     RootNfcSupport.clearStaleLease();
     runLocked(mode,seconds,json,bytes);
    }
   }
  }
 }
 private void runLocked(String mode,int seconds,String json,byte[] bytes)throws Exception {
  final int uid=android.os.Process.myUid();
  JSONObject settings=new JSONObject(json);String transport=settings.optString("discoveryBackend","system");
  BackendPolicy.validate(uid,mode,transport);
  // Only Q-Poll sessions may opt into the native backend. Inspection/standard A never inject.
  if(!BackendPolicy.needsQPoll(mode))transport="system";
  log("QP|BRIDGE_STATE|Shizuku UID="+uid+"；worker UID=2000；寻卡后端="+transport);
  File dir=new File("/data/local/tmp/qpoll-bridge-"+android.os.Process.myPid()+"-"+System.nanoTime());if(!dir.mkdir())throw new IOException("Cannot create session directory");
  File mark=new File(dir,"stop"),job=new File(dir,"job.json"),image=new File(dir,"image.bin");stop=mark;
  RootNfcSupport hook=null;
  java.lang.Process child=null;
  try{
   checked("chmod","700",dir.toString());
   if(cancelled){log("QP|CANCELLED|已停止，未启动 NFC worker");return;}
   if(bytes.length>0)settings.put("imageFile",image.toString());
   try(FileOutputStream out=new FileOutputStream(image)){out.write(bytes);}try(FileOutputStream out=new FileOutputStream(job)){out.write(settings.toString().getBytes("UTF-8"));}
   if(transport.equals("sn220")){hook=new RootNfcSupport(apk,this::log);hook.prepare();}
   if(cancelled){log("QP|CANCELLED|已停止，未启动 NFC worker");return;}
   if(uid==0){
    if(!new File("/system/bin/runcon").canExecute())throw new IOException("Root 后端需要 runcon 切换到 shell SELinux 域");
    checked("chown","-R","2000:2000",dir.toString());
    checked("chcon","-R","u:object_r:shell_data_file:s0",dir.toString());
   }
   java.util.List<String> command=BackendPolicy.workerCommand(uid,apk,mode,seconds,mark.toString(),"/data/local/tmp/qpolllab-worker.lock",job.toString(),transport);
   ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);builder.environment().put("CLASSPATH",apk);child=builder.start();final java.lang.Process running=child;
   Thread deadline=new Thread(()->{try{if(!running.waitFor(320,TimeUnit.SECONDS)){mark.createNewFile();if(!running.waitFor(10,TimeUnit.SECONDS))running.destroy();}}catch(Exception ignored){}});deadline.setDaemon(true);deadline.start();
   try(BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),"UTF-8"))){String line;while((line=in.readLine())!=null){
    log(line);
    if(hook!=null&&line.startsWith("QP|HOOK_RESTORE_REQUEST|")){
     try{hook.beforeReaderRestore();new File(dir,"hook-off-ack").createNewFile();log("QP|HOOK_RESTORE_ACK|已关闭兼容改写，允许恢复普通寻卡");}
     catch(Exception e){log("QP|RESTORE_FAILED|恢复握手失败："+e);}
    }
   }}
   int exit=child.waitFor();if(exit!=0)log("QP|PROCESS_EXIT|"+exit);
  }finally{
   try{mark.createNewFile();if(child!=null&&!child.waitFor(10,TimeUnit.SECONDS)){
       child.destroy();if(!child.waitFor(2,TimeUnit.SECONDS))child.destroyForcibly();
       log("QP|RESTORE_FAILED|worker 未正常结束，请关闭再开启 NFC");
   }}finally{
    try{if(hook!=null)hook.close();}finally{stop=null;job.delete();image.delete();mark.delete();new File(dir,"hook-off-ack").delete();new File(dir,"session.lock").delete();dir.delete();}
   }
  }
 }
 private static void checked(String... command)throws Exception {
  java.lang.Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
  if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("权限准备超时："+command[0]);}
  if(process.exitValue()!=0)throw new IOException("权限准备失败："+command[0]);
 }
}
