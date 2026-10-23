package org.nfcresearch.qpolllab;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Android ISO-DEP transport; link segmentation and WTX remain in the platform stack. */
public final class PanelSession {
    public interface Events {void emit(String kind,String message);}
    public static void run(Tag tag,String mode,File jobFile,File stopFile,Events events) throws Exception {
        if(!BackendPolicy.runsPanelProtocol(mode))throw new IllegalArgumentException("当前模式不允许屏幕协议通信");
        byte[] config=read(jobFile,8192);JSONObject job=new JSONObject(new String(config,"UTF-8"));
        byte[] image=null;
        if(mode.equals("refresh")) {
            PanelType type=PanelType.parse(job.optString("panelType",""));
            events.emit("PANEL_TYPE","用户选择="+type+"；D1 不识别实体屏幕型号；保留板内驱动");
            File data=new File(job.getString("imageFile")).getCanonicalFile();
            if(!data.getParentFile().equals(jobFile.getCanonicalFile().getParentFile()))throw new IllegalArgumentException("图像快照路径错误");
            image=read(data,PanelProtocol.IMAGE_BYTES);
            if(image.length!=PanelProtocol.IMAGE_BYTES||!PanelProtocol.toHex(MessageDigest.getInstance("SHA-256").digest(image)).equalsIgnoreCase(job.getString("sha256")))
                throw new IllegalArgumentException("预览与图片快照校验不符，未发送 APDU");
        }
        final int chargeMs=mode.equals("refresh")?job.optInt("chargeMs",0):0;
        if(chargeMs<0||chargeMs>PanelProtocol.MAX_CHARGE_MS)throw new IllegalArgumentException("发送前充能时间必须为 0–30 秒");
        final long deadline=SystemClock.elapsedRealtime()+(mode.equals("refresh")?180000+chargeMs:15000);
        PanelProtocol.Control control=new PanelProtocol.Control() {
            public long now(){return SystemClock.elapsedRealtime();}
            public void sleep(long ms)throws Exception{Thread.sleep(ms);}
            public void check()throws Exception {
                if(stopFile.exists())throw new InterruptedException("已停止；当前屏幕画面需目视确认");
                if(now()>deadline)throw new IllegalStateException("本轮通信超过总预算，未重试写入");
            }
            public void event(String kind,String message){events.emit(kind,message);}
        };
        control.check();IsoDep iso=IsoDep.get(tag);
        if(iso==null)throw new IllegalArgumentException("目标未提供 ISO-DEP，未发送 APDU");
        try {
            long started=SystemClock.elapsedRealtime();
            events.emit("STAGE","connecting|正在建立 ISO-DEP 连接");
            events.emit("ISO_DEP","历史字节="+PanelProtocol.toHex(iso.getHistoricalBytes()==null?new byte[0]:iso.getHistoricalBytes())+"；高层响应="+PanelProtocol.toHex(iso.getHiLayerResponse()==null?new byte[0]:iso.getHiLayerResponse()));
            try {iso.connect();}
            catch(java.io.IOException e){
                events.emit("CONNECT_FAILED","耗时="+(SystemClock.elapsedRealtime()-started)+" ms；"+e);
                throw new ConnectionRetry.BeforeApdu(e);
            }
            events.emit("CONNECTED","ISO-DEP 已连接；耗时="+(SystemClock.elapsedRealtime()-started)+" ms；最大传输="+iso.getMaxTransceiveLength());
            if(iso.getMaxTransceiveLength()<257)throw new IllegalArgumentException("平台单次 APDU 长度不足 257 字节");
            PanelProtocol protocol=new PanelProtocol((command,timeout)->{
                control.check();int remaining=(int)Math.max(1,deadline-control.now());iso.setTimeout(Math.min(timeout,remaining));
                String ins=String.format("%02X",command[1]&255);
                if(command[1]!=(byte)0xD3&&command[1]!=(byte)0xDE)events.emit("APDU_BEGIN","INS="+ins+"；超时="+Math.min(timeout,remaining)+" ms");
                try {return iso.transceive(command);}
                catch(java.io.IOException e){events.emit("APDU_FAILED","INS="+ins+"；连接状态="+iso.isConnected()+"；"+e+"；不会自动重试协议操作");throw e;}
            },control);
            if(mode.equals("identity"))protocol.identify();
            else protocol.write(image,job.getInt("powerMs"),job.optInt("pollMs",PanelProtocol.DEFAULT_POLL_MS),chargeMs);
        } finally {try{iso.close();}catch(Exception e){events.emit("CLOSE_ERROR","关闭 ISO-DEP："+e);}}
    }
    private static byte[] read(File file,int max)throws Exception {
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){if(out.size()+n>max)throw new IllegalArgumentException("文件超出大小上限");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
}
