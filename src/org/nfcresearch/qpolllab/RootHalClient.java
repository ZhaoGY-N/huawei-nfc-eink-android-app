package org.nfcresearch.qpolllab;

import android.os.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** App-side lifetime owner. The pipe/Binder never carries images or arbitrary commands. */
final class RootHalClient {
    private final IBinder binder;
    private final java.lang.Process child;
    private final Consumer<String> log;
    private final Binder token=new Binder();
    private final Object state=new Object(),outputLock=new Object();
    private volatile boolean ready,disabled,done,closed,disableRequested;
    private volatile String error;
    private RootHalClient(IBinder binder,java.lang.Process child,Consumer<String> log){this.binder=binder;this.child=child;this.log=log;}
    static RootHalClient start(int manager,String apk,IBinder binder,boolean inject,Consumer<String> log)throws Exception {
        java.lang.Process child=null;
        if(manager!=0){String script=RootHalBridge.launchScript(apk,inject);child=manager==2?new ProcessBuilder("su","-c",script).redirectErrorStream(true).start():ShizukuProcess.start(script);}
        RootHalClient client=new RootHalClient(manager==0?binder:null,child,log);
        if(manager==0){
            if(binder==null||!binder.isBinderAlive())throw new IOException("请先连接 Shizuku Root 用户服务");
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{data.writeInterfaceToken(RootHalBridge.INTERFACE);data.writeStrongBinder(client.token);data.writeInt(inject?1:0);if(!binder.transact(1,data,reply,0))throw new IOException("HAL 后端不兼容");reply.readException();}
            finally{data.recycle();reply.recycle();}
        }
        client.monitor();return client;
    }
    private void line(String line){
        if(line.startsWith("QP|HAL_READY|"))ready=true;
        if(line.startsWith("QP|HAL_DISABLED|")){disabled=true;if(!disableRequested)error="Root HAL 会话提前结束，已停止前台通信";}
        if(line.startsWith("QP|HAL_DONE|"))done=true;
        if(line.startsWith("QP|ERROR|")||line.startsWith("QP|RESTORE_FAILED|"))error=line.substring(line.indexOf('|',3)+1);
        log.accept(line);synchronized(state){state.notifyAll();}
    }
    private void failed(Exception e){if(!closed){error="Root HAL 管理通道断开："+e;log.accept("QP|ERROR|"+error);}done=true;synchronized(state){state.notifyAll();}}
    private void monitor(){
        Thread reader=new Thread(()->{
            try{
                if(binder!=null){
                    boolean running=true;
                    while(running&&!closed){
                        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                        try{data.writeInterfaceToken(RootHalBridge.INTERFACE);if(!binder.transact(2,data,reply,0))throw new IOException("HAL 轮询接口不可用");reply.readException();String lines=reply.readString();running=reply.readInt()!=0;if(lines!=null)for(String value:lines.split("\n"))line(value);}
                        finally{data.recycle();reply.recycle();}
                        if(running)SystemClock.sleep(100);
                    }
                }else{
                    try(BufferedReader input=new BufferedReader(new InputStreamReader(child.getInputStream(),"UTF-8"))){String value;while((value=input.readLine())!=null)line(value);}
                    int exit=child.waitFor();if(exit!=0)throw new IOException("HAL 进程退出码="+exit);
                }
                if(!done&&!closed)throw new IOException("未收到 HAL 会话结束确认");
            }catch(Exception e){failed(e);}finally{done=true;synchronized(state){state.notifyAll();}}
        },"hal-client-reader");reader.setDaemon(true);reader.start();
        if(child!=null){
            Thread heartbeat=new Thread(()->{try{while(!closed&&!done){command(HalLease.HEARTBEAT);SystemClock.sleep(1000);}}catch(Exception e){failed(e);}},"hal-heartbeat");heartbeat.setDaemon(true);heartbeat.start();
            Thread stderr=new Thread(()->{try(BufferedReader input=new BufferedReader(new InputStreamReader(child.getErrorStream(),"UTF-8"))){String value;while((value=input.readLine())!=null)log.accept(value);}catch(IOException ignored){}},"hal-stderr");stderr.setDaemon(true);stderr.start();
        }
    }
    private void command(int value)throws Exception {
        synchronized(outputLock){
            if(binder!=null){Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(RootHalBridge.INTERFACE);data.writeInt(value);if(!binder.transact(3,data,reply,0))throw new IOException("HAL 控制接口不可用");reply.readException();}finally{data.recycle();reply.recycle();}}
            else {child.getOutputStream().write(value);child.getOutputStream().flush();}
        }
    }
    void awaitReady(java.util.function.BooleanSupplier cancelled)throws Exception {
        long end=SystemClock.elapsedRealtime()+90000;
        synchronized(state){while(!ready){if(cancelled.getAsBoolean())throw new InterruptedException("已停止，尚未开启 ReaderMode");if(error!=null||done)throw new IOException(error==null?"HAL 未就绪便已结束":error);if(SystemClock.elapsedRealtime()>=end)throw new IOException("HAL 准备超时");state.wait(100);}}
        check();
    }
    void check()throws IOException {if(error!=null||done||disabled)throw new IOException(error==null?"HAL 会话已结束":error);}
    void requestDisable(){disableRequested=true;if(!done)try{command(HalLease.DISABLE);}catch(Exception e){failed(e);}}
    boolean disable()throws Exception {
        disableRequested=true;
        if(done)return disabled;
        command(HalLease.DISABLE);long end=SystemClock.elapsedRealtime()+25000;
        synchronized(state){while(!disabled&&!done&&SystemClock.elapsedRealtime()<end)state.wait(100);}
        return disabled;
    }
    boolean finish() {
        try{
            if(!done){command(HalLease.RELEASE);long end=SystemClock.elapsedRealtime()+30000;synchronized(state){while(!done&&SystemClock.elapsedRealtime()<end)state.wait(100);}}
            return done&&error==null;
        }catch(Exception e){failed(e);return false;}
        finally{
            closed=true;
            if(child!=null){try{child.getOutputStream().close();}catch(IOException ignored){}
                try{if(!child.waitFor(5,TimeUnit.SECONDS))child.destroy();}catch(InterruptedException e){Thread.currentThread().interrupt();child.destroy();}
                try{child.getInputStream().close();child.getErrorStream().close();}catch(IOException ignored){}
            }
        }
    }
}
