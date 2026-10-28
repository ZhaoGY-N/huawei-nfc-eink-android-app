package org.nfcresearch.qpolllab;

import android.app.Activity;
import android.nfc.*;
import android.nfc.tech.IsoDep;
import android.os.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Public foreground ReaderMode; the only privileged operation is HAL preparation. */
final class ForegroundNfcSession {
    private final Activity activity;
    private final Handler main;
    private final BooleanSupplier foreground;
    private final Consumer<String> output;
    private final File stop;
    private final AtomicReference<Tag> detected=new AtomicReference<>();
    private final AtomicReference<IsoDep> connection=new AtomicReference<>();
    private volatile boolean cancelled,finished,readerTouched,arming;
    private volatile String channelFailure;
    private volatile RootHalClient hal;
    ForegroundNfcSession(Activity activity,Handler main,BooleanSupplier foreground,File stop,Consumer<String> output){
        this.activity=activity;this.main=main;this.foreground=foreground;this.stop=stop;this.output=output;
    }
    boolean readerActive(){return readerTouched||arming;}
    void cancel(){cancelled=true;try{stop.createNewFile();}catch(IOException ignored){}RootHalClient client=hal;if(client!=null)client.requestDisable();}
    private boolean stopped(){return cancelled||stop.exists();}
    private void emit(String kind,String message){output.accept("QP|"+kind+"|"+message.replace('\n',' '));}
    private void check()throws Exception {
        if(channelFailure!=null)throw new IOException(channelFailure);
        if(stopped())throw new InterruptedException("前台会话已停止；不会自动重发");
        if(hal!=null)hal.check();
    }
    private void ui(Runnable operation)throws Exception {
        FutureTask<Void> task=new FutureTask<>(()->{operation.run();return null;});main.post(task);
        try{task.get(10,TimeUnit.SECONDS);}catch(Exception e){task.cancel(false);throw e;}
    }
    private void enable(String mode)throws Exception {
        arming=true;
        try{ui(()->{
            if(stopped()||!foreground.getAsBoolean())throw new IllegalStateException("应用已离开前台，未开启 ReaderMode");
            NfcAdapter adapter=NfcAdapter.getDefaultAdapter(activity);
            if(adapter==null||!adapter.isEnabled())throw new IllegalStateException("系统 NFC 未开启");
            Bundle options=new Bundle();options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,5000);
            readerTouched=true;
            adapter.enableReaderMode(activity,tag->{
                if(stopped())return;
                if(mode.equals("power")){emit("POWER_TAG","系统已激活标签；仅供能，不读取屏幕或发送 APDU");return;}
                if(detected.compareAndSet(null,tag)){
                    emit("TAG","UID="+PanelProtocol.toHex(tag.getId())+"；技术="+Arrays.toString(tag.getTechList()));
                    if(BackendPolicy.needsQPoll(mode))emit("CAPABILITY","HAL 路径已收到系统标签回调；屏幕 D1 和完整刷新仍需分别确认");
                }
            },NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,options);
        });}finally{arming=false;}
    }
    private void disable()throws Exception {
        if(!readerTouched)return;
        ui(()->{NfcAdapter adapter=NfcAdapter.getDefaultAdapter(activity);if(adapter==null)throw new IllegalStateException("NFC 适配器已不可用");adapter.disableReaderMode(activity);readerTouched=false;});
        emit("READER_OFF","前台 ReaderMode 已释放");
    }
    private Tag awaitTag(int seconds)throws Exception {
        long end=SystemClock.elapsedRealtime()+seconds*1000L;
        while(detected.get()==null&&SystemClock.elapsedRealtime()<end){check();Thread.sleep(50);}check();return detected.get();
    }
    void run(String mode,int seconds,int manager,IBinder binder,File job)throws Exception {
        BackendPolicy.validateDuration(mode,seconds);
        emit("APP_IDENTITY","前台 App UID="+android.os.Process.myUid()+"；公开 enableReaderMode；Root 仅管理 HAL");
        if(mode.equals("inspect")){
            if(!foreground.getAsBoolean())throw new IOException("请保持 App 在前台");
            emit("CHIP",ChipInfo.inspect());emit("READY","前台 NFC 路径已选择；Root / HAL / 标签与完整刷新尚需实测");return;
        }
        Thread guard=new Thread(()->{
            while(!finished){
                if(hal!=null&&!stopped())try{hal.check();}catch(Exception e){channelFailure=e.getMessage();cancel();}
                if(stopped()){
                    IsoDep iso=connection.getAndSet(null);if(iso!=null)try{iso.close();}catch(IOException ignored){}
                }
                SystemClock.sleep(50);
            }
        },"foreground-nfc-guard");guard.setDaemon(true);
        try{
            check();
            hal=RootHalClient.start(manager,activity.getApplicationInfo().sourceDir,binder,BackendPolicy.needsQPoll(mode),output);
            hal.awaitReady(this::stopped);check();guard.start();
            enable(mode);emit("START",mode+":"+seconds);
            if(mode.equals("power")){
                PowerSession.hold(seconds,new PowerSession.Control(){
                    public long now(){return SystemClock.elapsedRealtime();}public boolean cancelled(){return stopped();}
                    public void check()throws Exception{if(channelFailure!=null)throw new IOException(channelFailure);hal.check();}
                    public void sleep(long ms)throws Exception{Thread.sleep(ms);}public void event(String k,String m){emit(k,m);}
                });
            }else{
                Tag tag=awaitTag(seconds);
                if(tag==null){if(BackendPolicy.needsQPoll(mode))emit("CAPABILITY","HAL 路径本轮未收到标签；不能据此认定硬件不支持");emit("RESULT","本轮超时，未收到标签；不能据此认定硬件不支持");}
                else if(BackendPolicy.runsPanelProtocol(mode)){
                    byte[] uid=tag.getId().clone();
                    ConnectionRetry.run(()->{
                        check();Tag current=detected.get();if(current==null||!Arrays.equals(uid,current.getId()))throw new IOException("标签已改变，尚未发送 APDU");
                        PanelSession.run(current,mode,job,stop,this::emit,connection::set);
                    },()->{
                        check();disable();detected.set(null);Thread.sleep(200);check();enable(mode);emit("START",mode+":"+seconds);
                        if(awaitTag(seconds)==null)throw new IOException("连接前重新寻卡超时；未发送 APDU");
                    });
                    if(mode.equals("identity"))emit("RESULT","D1 屏幕规格与原厂配置校验通过，未写图像");
                }else emit("RESULT","已收到标签；前台公开 API 寻卡完成，未发送 APDU");
            }
        }catch(Exception e){if(channelFailure!=null)throw new IOException(channelFailure,e);if(!stopped())throw e;emit("CANCELLED","前台会话已停止；请检查屏幕画面，不会自动重发");}
        finally{
            // Close panel I/O, turn off rewriting, then let ReaderMode restore ordinary polling.
            finished=true;
            connection.set(null);
            boolean restored=ForegroundCleanup.run(()->hal==null||hal.disable(),()->{disable();return true;},()->hal==null||hal.finish(),m->emit("RESTORE_FAILED",m));
            if(restored)emit("RESTORED","前台 ReaderMode 与 Root HAL 会话已释放");
        }
    }
}
