package org.nfcresearch.qpolllab;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Explicit experimental backend; every raw command requires its on-wire response. */
final class DirectQPoll {
    interface Transport { int send(int gid, int oid, byte[] payload) throws Exception; }
    interface Reader { void set(int flags) throws Exception; }
    interface Log { void line(String kind, String message); }
    private final Transport transport;
    private final Reader reader;
    private final BooleanSupplier cancelled;
    private final Log log;
    private final long timeoutMs;
    private String expectedTx;
    private boolean txSeen, normalTx, normalAccepted;
    private Integer response;
    private volatile String logFailure;
    private String conflict;
    private boolean readerTouched, rawTouched;
    private volatile boolean acceptTags;

    DirectQPoll(Transport transport, Reader reader, BooleanSupplier cancelled, Log log) {
        this(transport, reader, cancelled, log, 3000);
    }
    DirectQPoll(Transport transport, Reader reader, BooleanSupplier cancelled, Log log, long timeoutMs) {
        this.transport=transport; this.reader=reader; this.cancelled=cancelled;
        this.log=log; this.timeoutMs=timeoutMs;
    }
    boolean acceptsTags() { return acceptTags; }

    /** Called only by the HAL log reader. Ignore malformed and unrelated packets. */
    synchronized void observe(boolean sent, String packet) {
        packet=packet.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        if (!packet.matches("[0-9A-F]+") || packet.length()<6 || (packet.length()&1)!=0) return;
        int length=Integer.parseInt(packet.substring(4,6),16);
        if (packet.length()!=6+2*length) return;
        if (sent && packet.startsWith("2103")) {
            normalTx=!NciDiscovery.hasQPoll(packet);
            normalAccepted=false;
        }
        if (!sent && packet.startsWith("4103") && normalTx) {
            normalAccepted=packet.equals("41030100");
            normalTx=false;
        }
        if (expectedTx!=null && response==null) {
            if (sent && packet.equals(expectedTx)) txSeen=true;
            else if (sent && txSeen && (packet.startsWith("2103") || packet.startsWith("2106")))
                conflict="等待响应时出现其他寻卡命令，无法确认本次响应归属";
            else if (!sent && txSeen && packet.startsWith("41"+expectedTx.substring(2,4))) {
                if (length==1) response=Integer.parseInt(packet.substring(6,8),16);
                else conflict="控制器响应长度异常";
            }
        }
        notifyAll();
    }
    synchronized void logsUnavailable(String reason) { logFailure=reason; notifyAll(); }
    private synchronized void beginNormal() { normalTx=false; normalAccepted=false; }
    private void check(boolean restoring) throws Exception {
        if (!restoring && cancelled.getAsBoolean()) throw new InterruptedException("已停止直接 Q-Poll 寻卡");
        if (logFailure!=null) throw new IOException("无法验证控制器响应："+logFailure);
    }
    private void awaitNormal(boolean restoring) throws Exception {
        long deadline=System.nanoTime()+timeoutMs*1000000L;
        synchronized (this) {
            while (!normalAccepted) {
                check(restoring);
                if (System.nanoTime()>=deadline) throw new IOException("未确认系统普通寻卡命令及成功响应");
                wait(25);
            }
        }
    }
    private void command(int oid, byte[] payload, boolean restoring) throws Exception {
        StringBuilder packet=new StringBuilder(String.format(java.util.Locale.ROOT,"21%02X%02X",oid,payload.length));
        for (byte value:payload) packet.append(String.format(java.util.Locale.ROOT,"%02X",value&255));
        synchronized (this) {
            // Losing diagnostics must not prevent a best-effort RF_DEACTIVATE on exit.
            if (!restoring) check(false);
            expectedTx=packet.toString(); txSeen=false; response=null; conflict=null;
        }
        try {
            int hostStatus=transport.send(1,oid,payload);
            log.line("DIRECT_API", "OID="+oid+"；API 状态="+hostStatus+"（等待控制器响应）");
            if (hostStatus!=0) throw new IOException("直接寻卡命令提交失败，API 状态="+hostStatus);
            long deadline=System.nanoTime()+timeoutMs*1000000L;
            synchronized (this) {
                while (response==null && conflict==null) {
                    check(restoring);
                    if (System.nanoTime()>=deadline) throw new IOException("未确认命令 "+packet+" 的发送及控制器响应");
                    wait(25);
                }
                if (conflict!=null) throw new IOException(conflict);
                if (response!=0) throw new IOException("命令 "+packet+" 被拒绝，控制器状态="
                        +String.format(java.util.Locale.ROOT,"%02X",response));
            }
            log.line("DIRECT_ACK", packet+" → 控制器 STATUS_OK");
        } finally {
            synchronized (this) { expectedTx=null; }
        }
    }
    void start() throws Exception {
        if (readerTouched || rawTouched) throw new IllegalStateException("直接寻卡尚未恢复");
        check(false);
        beginNormal();
        readerTouched=true;
        reader.set(0x81);
        // Wait for the framework's own discovery to settle before overriding it.
        awaitNormal(false);
        rawTouched=true;
        command(6,new byte[]{0},false); // 21 06 01 00: RF_DEACTIVATE to idle.
        check(false);
        acceptTags=true;
        command(3,new byte[]{1,0x71,1},false); // 21 03 03 01 71 01: Q-Poll.
        log.line("CAPABILITY", "已确认控制器接受直接 Q-Poll 寻卡（0x71）；标签回调与刷新需单独验证。");
    }
    void close() throws Exception {
        acceptTags=false;
        Exception failure=null;
        if (rawTouched) {
            try {
                command(6,new byte[]{0},true);
                rawTouched=false;
                log.line("DIRECT_OFF", "已确认直接 Q-Poll 停止");
            } catch (Exception e) { failure=e; }
        }
        if (readerTouched) {
            try {
                beginNormal();
                reader.set(0);
                awaitNormal(true);
                readerTouched=false;
                log.line("READER_OFF", "ReaderMode 已释放，已确认系统普通寻卡响应");
            } catch (Exception e) {
                if (failure==null) failure=e; else failure.addSuppressed(e);
            }
        }
        if (failure!=null) throw failure;
    }
}
