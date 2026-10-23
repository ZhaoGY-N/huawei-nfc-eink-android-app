package org.nfcresearch.qpolllab;

import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded root helper for observation, identity reads, and explicitly requested image refresh. */
public final class QPollWorker {
    private static Object service, callback;
    private static Class<?> adapterType;
    private static ReaderModeCompat readerMode;
    private static final Binder token = new Binder();
    private static final AtomicBoolean stopping = new AtomicBoolean();
    private static final AtomicBoolean cleaned = new AtomicBoolean();
    private static final Object cleanupLock=new Object();
    private static final AtomicBoolean tagSeen = new AtomicBoolean();
    private static volatile boolean readerTouched, qtagTouched, readerReleased, qtagDisabled;
    private static int discoveredCode, lostCode = -1;
    private static Process logcat;
    private static volatile Tag detectedTag;
    private static volatile String qtagRejection;
    private static volatile boolean qDiscoverySent,qDiscoveryAccepted;
    private static String mode;
    private static boolean halDiscovery;
    private static boolean legacyRoot,directDiscovery;
    private static DirectQPoll directSession;
    private static boolean hookRestored=true;
    private static File hookOffAck;
    private static final Binder callbackBinder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString("android.nfc.IAppCallback");
                return true;
            }
            if (code != discoveredCode && code != lostCode) return super.onTransact(code, data, reply, flags);
            data.enforceInterface("android.nfc.IAppCallback");
            if(directSession!=null&&!directSession.acceptsTags()){if(reply!=null)reply.writeNoException();return true;}
            if (mode.equals("power") && code == lostCode) {
                tagSeen.set(false);
                emit("POWER_LOST", "标签已离场；请移动线圈并观察电压，等待系统重新寻卡");
                if (reply != null) reply.writeNoException();
                return true;
            }
            Tag tag = data.readInt() == 0 ? null : Tag.CREATOR.createFromParcel(data);
            if (mode.equals("power") && code == discoveredCode && tag != null) {
                tagSeen.set(true);
                emit("POWER_TAG", "系统已激活标签；保持供能会话，不读取 UID、屏幕规格或发送 APDU");
            } else if (code == discoveredCode && tag != null && tagSeen.compareAndSet(false,true)) {
                detectedTag=tag;
                emit("TAG", "收到标签 UID=" + hex(tag.getId()) + "；技术=" + Arrays.toString(tag.getTechList()));
                NfcA a = NfcA.get(tag);
                if (a != null) emit("META", "ATQA=" + hex(a.getAtqa()) + "；SAK=" + String.format("%04X", a.getSak() & 0xffff));
                stopping.set(true);
            } else if (code == lostCode) {
                emit("LOST", "收到标签离场通知（不代表已读到目标屏）");
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private static synchronized void emit(String kind, String message) {
        System.out.println("QP|" + kind + "|" + message.replace('\n', ' '));
        System.out.flush();
    }
    private static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02X", b & 255));
        return s.toString();
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        try { return adapterType.getMethod(name, types).invoke(service, args); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    private static void reader(int flags) throws Exception {
        Bundle extras=new Bundle();
        if(BackendPolicy.runsPanelProtocol(mode)||"power".equals(mode))extras.putInt("presence",5000);
        try{readerMode.set(service,token,callback,flags,extras,"com.android.shell");}
        catch(Exception e){emit("READER_CALL_FAILED","ReaderMode 调用失败（flags="+flags+"）："+e);throw e;}
    }
    private static void setupReaderMode()throws Exception {
        try{
            readerMode=ReaderModeCompat.resolve(adapterType,IBinder.class,Class.forName("android.nfc.IAppCallback"),Bundle.class);
            emit("READER_API","使用 setReaderMode "+readerMode.parameterCount()+" 参数接口");
        }catch(NoSuchMethodException e){emit("READER_API_MISSING",e.getMessage());throw e;}
    }
    private static int qtag(int action) throws Exception {
        return vendor(15,112,new byte[]{0x31,(byte)action});
    }
    private static int vendor(int gid,int oid,byte[] payload)throws Exception {
        return (Integer) invoke("sendVendorNciMessage", new Class<?>[]{int.class,int.class,int.class,byte[].class},
                1, gid, oid, payload);
    }
    private static void init() throws Exception {
        String domain=new String(java.nio.file.Files.readAllBytes(new File("/proc/self/attr/current").toPath()),"UTF-8").replace("\u0000","").trim();
        BackendPolicy.validateWorker(android.os.Process.myUid(),domain,legacyRoot);
        emit("WORKER_IDENTITY","UID="+android.os.Process.myUid()+"；SELinux="+domain+"；旧版Root直连="+legacyRoot);
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "nfc");
        if (binder == null) throw new IllegalStateException("未找到 NFC 服务");
        adapterType = Class.forName("android.nfc.INfcAdapter");
        service = Class.forName("android.nfc.INfcAdapter$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        int state = (Integer) invoke("getState", new Class<?>[0]);
        emit("STATE", "执行身份 UID="+android.os.Process.myUid()+"；NFC 状态=" + state + (state == 3 ? "（已开启）" : "（未开启）"));
        if (state != 3) throw new IllegalStateException("请先在系统设置中开启 NFC");
    }
    private static void setupCallback() throws Exception {
        Class<?> type = Class.forName("android.nfc.IAppCallback");
        Class<?> stub = Class.forName("android.nfc.IAppCallback$Stub");
        Field field = stub.getDeclaredField("TRANSACTION_onTagDiscovered");
        field.setAccessible(true); discoveredCode = field.getInt(null);
        try { field = stub.getDeclaredField("TRANSACTION_onTagLost"); field.setAccessible(true); lostCode = field.getInt(null); }
        catch (NoSuchFieldException ignored) { }
        callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("asBinder")) return callbackBinder;
            if (method.getName().equals("toString")) return "QPollLabCallback";
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("equals")) return object == args[0];
            return null;
        });
    }
    private static void startLogcat() {
        try {
            String since=new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",java.util.Locale.US).format(new java.util.Date());
            logcat = new ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "-T", since,
                    "NxpNciX:I", "NxpNciR:I", "*:S").redirectErrorStream(true).start();
            Thread monitor = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        int marker = line.indexOf("> ");
                        if (marker < 0) continue;
                        String packet = line.substring(marker + 2).replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
                        if(directSession!=null)directSession.observe(line.contains("NxpNciX"),packet);
                        if(!cleaned.get()&&line.contains("NxpNciX")&&NciDiscovery.hasQPoll(packet)){qDiscoverySent=true;emit("CAPABILITY","已发出 0x71 Q-Poll 寻卡，等待控制器确认。");}
                        if(!directDiscovery&&!cleaned.get()&&qDiscoverySent&&line.contains("NxpNciR")&&packet.startsWith("410301")&&packet.length()>=8){
                            if(packet.substring(6,8).equals("00")){qDiscoveryAccepted=true;emit("CAPABILITY","已确认控制器接受 Q-Poll 寻卡（0x71）；目标屏通信与刷新需单独验证。");}
                            else emit("CAPABILITY","Q-Poll 寻卡被拒绝，响应状态="+packet.substring(6,8));
                        }
                        if(qtagTouched&&line.contains("NxpNciR")&&packet.startsWith("4F7001")&&packet.length()>=8&&!cleaned.get()){
                            String status=packet.substring(6,8);
                            if(!status.equals("00")){qtagRejection="系统拒绝 QTag，响应状态="+status+"；本次 QTag 路径失败，不能据此判定硬件不支持 Q-Poll。";stopping.set(true);emit("QTAG_REJECTED",qtagRejection);}
                        }
                        if (line.contains("NxpNciX") && packet.startsWith("2103")) emit("RF_TX", packet);
                        if (line.contains("NxpNciR") && (packet.startsWith("4103") || packet.startsWith("6103") || packet.startsWith("6105"))) emit("RF_RX", packet);
                    }
                    if(directSession!=null)directSession.logsUnavailable("HAL 日志流已结束");
                } catch (Exception e) {if(directSession!=null)directSession.logsUnavailable(e.toString());}
            }, "nci-monitor");
            monitor.setDaemon(true); monitor.start();
        } catch (Exception e) {if(directSession!=null)directSession.logsUnavailable(e.toString());emit("LOG_UNAVAILABLE", "无法读取 HAL 日志；仍可观察标签回调：" + e); }
    }
    private static void cleanup() {
        synchronized(cleanupLock){
        if (!cleaned.compareAndSet(false, true)) return;
        if(halDiscovery&&readerTouched){
            emit("HOOK_RESTORE_REQUEST","先读取注入统计并关闭兼容开关，再恢复普通寻卡");
            long deadline=SystemClock.elapsedRealtime()+20000;
            while(hookOffAck!=null&&!hookOffAck.exists()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
            hookRestored=hookOffAck!=null&&hookOffAck.exists();
            if(!hookRestored)emit("RESTORE_FAILED","未收到 Root 兼容层关闭确认，请重启 NFC");
        }
        if (readerTouched) {
            try {if(directSession!=null)directSession.close();else {reader(0);emit("READER_OFF", "ReaderMode 已释放");}readerReleased = true;}
            catch (Exception e) { emit("CLEANUP_ERROR", "释放 ReaderMode 失败：" + e); }
        }
        if (qtagTouched) {
            try {
                int status = qtag(0);
                qtagDisabled = status == 0;
                emit(status == 0 ? "QTAG_OFF" : "CLEANUP_ERROR", "关闭 QTag，API 状态=" + status);
            } catch (Exception e) { emit("CLEANUP_ERROR", "关闭 QTag 失败：" + e); }
        }
        SystemClock.sleep(150);
        if (logcat != null) logcat.destroy();
        }
    }
    public static void main(String[] args) {
        int exit = 0;
        boolean sessionStarted = false;
        try {
            if (args.length != 6 && args.length != 7)
                throw new IllegalArgumentException("Invalid mode or argument count");
            legacyRoot=args.length==7;
            if(legacyRoot&&!args[6].equals("legacy-root"))throw new IllegalArgumentException("Unknown worker identity");
            mode=args[0];
            BackendPolicy.validate(android.os.Process.myUid(),mode,"system");
            if(!args[5].equals("sn220")&&!args[5].equals("system")&&!args[5].equals("direct"))throw new IllegalArgumentException("Unknown discovery backend");
            halDiscovery=args[5].equals("sn220")&&BackendPolicy.needsQPoll(mode);
            directDiscovery=args[5].equals("direct")&&BackendPolicy.needsQPoll(mode);
            hookRestored=!halDiscovery;
            boolean panelOperation=BackendPolicy.runsPanelProtocol(mode);
            boolean powerOnly=mode.equals("power");
            int seconds = Integer.parseInt(args[1]);
            BackendPolicy.validateDuration(mode,seconds);
            File stopFile = new File(args[2]);
            hookOffAck=new File(stopFile.getParentFile(),"hook-off-ack");
            if (stopFile.exists()) { emit("END", "已取消，未启动寻卡"); return; }
            // Bridge prepares a shell-owned lock; it survives bridge death while this worker runs.
            try (RandomAccessFile lockFile = new RandomAccessFile(args[3], "rw"); FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("另一轮测试正在运行");
                try {
                init();
                if (args[0].equals("inspect")) {
                    emit("CHIP",ChipInfo.inspect());
                    adapterType.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
                    emit("READY", "NXP 测试入口存在，可开始测试（尚未验证空口能力）");
                    emit("END", "权限检查完成，未发送寻卡命令"); return;
                }
                if (stopFile.exists()) { emit("END", "已取消，未启动寻卡"); return; }
                setupCallback();
                setupReaderMode();
                if(directDiscovery){
                    adapterType.getMethod("sendVendorNciMessage",int.class,int.class,int.class,byte[].class);
                    directSession=new DirectQPoll(QPollWorker::vendor,QPollWorker::reader,stopFile::exists,QPollWorker::emit);
                    emit("DIRECT","手动选择直接 Q-Poll；控制器响应、标签回调与刷屏分别验证");
                }
                Runtime.getRuntime().addShutdownHook(new Thread(QPollWorker::cleanup));
                startLogcat();
                if (BackendPolicy.needsQPoll(mode)&&!halDiscovery&&!directDiscovery) {
                    qtagTouched = true;
                    int status = qtag(1);
                    emit("QTAG_ON", "启用 QTag，API 状态=" + status + "（只是主机状态）");
                    if (status != 0) throw new IllegalStateException("QTag 启用失败");
                }
                if(halDiscovery)emit("HOOK","使用 HAL Q-Poll 兼容层，跳过系统 QTag 命令");
                readerTouched = true;
                if(directSession!=null){directSession.start();qDiscoveryAccepted=true;}else reader(0x81);
                sessionStarted = true;
                emit("START", args[0] + ":" + seconds);
                if(powerOnly) {
                    PowerSession.hold(seconds,new PowerSession.Control() {
                        public long now(){return SystemClock.elapsedRealtime();}
                        public boolean cancelled(){return stopFile.exists();}
                        public void check(){if(qtagRejection!=null)throw new IllegalStateException(qtagRejection);}
                        public void sleep(long ms){SystemClock.sleep(ms);}
                        public void event(String kind,String message){emit(kind,message);}
                    });
                } else {
                long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                while (!stopping.get() && !stopFile.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                if(qtagRejection!=null)throw new IllegalStateException(qtagRejection);
                if((mode.equals("qtag")||panelOperation)&&!qDiscoveryAccepted)emit("CAPABILITY","尚未确认 Q-Poll：未获得 0x71 指令及成功响应的完整证据；请查看日志。");
                if (tagSeen.get()&&panelOperation) {
                    final int scanSeconds=seconds;
                    final File panelJob=new File(args[4]);
                    final byte[] targetUid=detectedTag.getId().clone();
                    final int[] connectionAttempt={0};
                    ConnectionRetry.run(()->{
                        if(stopFile.exists())throw new InterruptedException("已停止，未开始协议通信");
                        if(!Arrays.equals(targetUid,detectedTag.getId()))throw new java.io.IOException("重新寻到不同 UID 的标签，停止；尚未发送 APDU");
                        emit("CONNECT_ATTEMPT",(++connectionAttempt[0])+" / 3");
                        PanelSession.run(detectedTag,mode,panelJob,stopFile,QPollWorker::emit);
                    },()->{
                        if(stopFile.exists())throw new InterruptedException("已停止重新寻卡");
                        emit("RECONNECT","尚未发送 APDU；重新寻卡以获取新标签，最多三次连接尝试");
                        if(directSession!=null)directSession.close();else reader(0);
                        SystemClock.sleep(200);
                        detectedTag=null;tagSeen.set(false);stopping.set(false);
                        if(stopFile.exists())throw new InterruptedException("已停止重新寻卡");
                        if(directSession!=null)directSession.start();else reader(0x81);
                        emit("START",mode+":"+scanSeconds);
                        long retryDeadline=SystemClock.elapsedRealtime()+scanSeconds*1000L;
                        while(!stopping.get()&&!stopFile.exists()&&SystemClock.elapsedRealtime()<retryDeadline)SystemClock.sleep(20);
                        if(stopFile.exists())throw new InterruptedException("已停止重新寻卡");
                        if(qtagRejection!=null)throw new IllegalStateException(qtagRejection);
                        if(!tagSeen.get())throw new java.io.IOException("连接失败后重新寻卡超时；尚未发送 APDU");
                    });
                    if(mode.equals("identity"))emit("RESULT","D1 屏幕规格与原厂配置校验通过，未写图像");
                }
                else if (tagSeen.get()) emit("RESULT", "已收到标签；只显示元信息，未发送 APDU");
                else if (stopFile.exists()) emit("RESULT", "用户停止；未收到标签");
                else emit("RESULT", "本轮超时，未收到标签；不能据此认定硬件不支持");
                }
                } finally { cleanup(); }
            }
        } catch (InterruptedException e) {
            emit("CANCELLED",e.getMessage());exit=3;
        } catch (Throwable e) {
            emit("ERROR", e.getClass().getSimpleName() + ": " + e.getMessage()); exit = 1;
            java.io.StringWriter trace=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(trace));
            for(String line:trace.toString().split("\n"))emit("TRACE",line.trim());
        } finally {
            cleanup();
            if (readerTouched || qtagTouched) {
                boolean ok = (!readerTouched || readerReleased) && (!qtagTouched || qtagDisabled) && (!halDiscovery || hookRestored);
                emit(ok ? "RESTORED" : "RESTORE_FAILED", ok ? "已恢复普通寻卡" : "恢复未确认，请重启 NFC 开关后再试");
                if (!ok) exit = 2;
            }
            if (sessionStarted || exit != 0) emit("END", "本轮结束");
        }
        System.exit(exit);
    }
}
