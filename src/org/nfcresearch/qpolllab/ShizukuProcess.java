package org.nfcresearch.qpolllab;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.*;
import java.util.concurrent.TimeUnit;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Uses the server AIDL newProcess API; the pinned public API has no wrapper factory. */
final class ShizukuProcess extends Process {
    private final IRemoteProcess remote;
    private final InputStream stdout,stderr;
    private final OutputStream stdin;
    static ShizukuProcess start(String script)throws Exception {
        IShizukuService service=IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if(service==null)throw new IOException("Shizuku 服务未连接");
        // A null environment inherits BOOTCLASSPATH, ANDROID_ROOT, etc.
        return new ShizukuProcess(service.newProcess(new String[]{"/system/bin/sh","-c",script},null,null));
    }
    private ShizukuProcess(IRemoteProcess remote)throws RemoteException,IOException {
        if(remote==null)throw new IOException("Shizuku 未返回进程");this.remote=remote;
        ParcelFileDescriptor out=null,in=null,err=null;
        try{
            out=remote.getOutputStream();in=remote.getInputStream();err=remote.getErrorStream();
            if(out==null||in==null)throw new IOException("Shizuku 进程管道不可用");
            stdin=new ParcelFileDescriptor.AutoCloseOutputStream(out);
            stdout=new ParcelFileDescriptor.AutoCloseInputStream(in);
            stderr=err==null?new ByteArrayInputStream(new byte[0]):new ParcelFileDescriptor.AutoCloseInputStream(err);
        }catch(RemoteException|IOException e){
            for(ParcelFileDescriptor fd:new ParcelFileDescriptor[]{out,in,err})if(fd!=null)try{fd.close();}catch(IOException ignored){}
            try{remote.destroy();}catch(RemoteException ignored){}throw e;
        }
    }
    public OutputStream getOutputStream(){return stdin;}
    public InputStream getInputStream(){return stdout;}
    public InputStream getErrorStream(){return stderr;}
    public int waitFor()throws InterruptedException {try{return remote.waitFor();}catch(RemoteException e){throw new IllegalStateException(e);}}
    public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException {try{return remote.waitForTimeout(timeout,unit.name());}catch(RemoteException e){throw new IllegalStateException(e);}}
    public int exitValue(){try{return remote.exitValue();}catch(RemoteException e){throw new IllegalStateException(e);}}
    public boolean isAlive(){try{return remote.alive();}catch(RemoteException e){return false;}}
    public void destroy(){try{remote.destroy();}catch(RemoteException ignored){}}
    public Process destroyForcibly(){destroy();return this;}
}
