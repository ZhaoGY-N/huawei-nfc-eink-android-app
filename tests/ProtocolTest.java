package org.nfcresearch.qpolllab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Hardware-free behavioral tests; all writes terminate at this fake transport. */
public final class ProtocolTest {
    static final byte[] OK=PanelProtocol.hex("9000");
    // Synthetic UID; profile bytes describe the supported screen configuration.
    static final byte[] INFO=PanelProtocol.hex("A007F0C72006000228A10700120030FFFFFFB10102B20114B30100C004201EA001C10401020304D107012000000000009000");
    static int cases;
    static void eq(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    interface Action {void run()throws Exception;}
    static void rejects(Action action,String message)throws Exception{try{action.run();throw new AssertionError("Expected rejection: "+message);}catch(AssertionError e){throw e;}catch(Exception expected){}}
    static final class Fake implements PanelProtocol.Control,PanelProtocol.Transport {
        long time,cancelAt=-1,firstUploadAt=-1;int d3,d4,de,pin,d8,d1,failD3=-1,cancelAtD3=-1,pollMs=500,deLatencyMs,chargeMs,powerMs=2000;
        boolean authNeeded,failPin,failD4,noBusy,foreverBusy,malformedBusy,cancel,cancelBeforeD4,cancelWhilePolling;
        byte[] info=INFO;
        ArrayList<String> events=new ArrayList<>();ArrayList<byte[]> uploads=new ArrayList<>();
        ArrayList<Long> deTimes=new ArrayList<>();
        public long now(){return time;}
        public void sleep(long ms){time+=ms;if(cancelAt>=0&&time>=cancelAt)cancel=true;}
        public void check()throws Exception{if(cancelAt>=0&&time>=cancelAt)cancel=true;if(cancel)throw new InterruptedException("cancelled");}
        public void event(String kind,String message){events.add(kind+"|"+message);if(cancelBeforeD4&&message.startsWith("refreshing|"))cancel=true;if(cancelWhilePolling&&kind.equals("BUSY")&&cancelAt<0)cancelAt=time+250;}
        public byte[] exchange(byte[] cmd,int timeout)throws Exception{
            switch(cmd[1]&255){
                case 0xD1:d1++;return info;
                case 0xD8:d8++;return authNeeded&&pin==0?PanelProtocol.hex("6985"):OK;
                case 0x20:pin++;eq(PanelProtocol.toHex(cmd).equals("00200001055265699919"),"published PIN APDU");return failPin?PanelProtocol.hex("63C2"):OK;
                case 0xD3:if(firstUploadAt<0)firstUploadAt=time;d3++;uploads.add(cmd.clone());if(d3==cancelAtD3)cancel=true;return d3==failD3?PanelProtocol.hex("6995"):OK;
                case 0xD4:d4++;String expected=powerMs==500?"F0D4858000":powerMs==1000?"F0D48A8000":powerMs==2000?"F0D4948000":powerMs==3000?"F0D49E8000":powerMs==4000?"F0D4A88000":"F0D4B28000";eq(PanelProtocol.toHex(cmd).equals(expected),"D4 power timing");eq(events.get(events.size()-1).equals("WAIT|power|"+time+"|"+powerMs),"power timer starts immediately before D4, after upload");return failD4?PanelProtocol.hex("6502"):OK;
                case 0xDE:deTimes.add(time);time+=deLatencyMs;de++;if(malformedBusy)return PanelProtocol.hex("029000");return PanelProtocol.hex((foreverBusy||(!noBusy&&de<=2))?"019000":"009000");
                default:throw new AssertionError("Unexpected command: "+PanelProtocol.toHex(cmd));
            }
        }
        void run()throws Exception{new PanelProtocol(this,this).write(new byte[PanelProtocol.IMAGE_BYTES],powerMs,pollMs,chargeMs);}
        boolean emitted(String kind){for(String e:events)if(e.startsWith(kind+"|"))return true;return false;}
    }
    static void passed(String label){cases++;System.out.println("PASS "+label);}
    static byte[] withAppId(String value){
        byte[] info=INFO.clone(),appId=PanelProtocol.hex(value);
        for(int i=0;i<info.length-2;){int tag=info[i++]&255,n=info[i++]&255;
            if(tag==0xC0){eq(appId.length==n,"APPID fixture length");System.arraycopy(appId,0,info,i,n);return info;}i+=n;}
        throw new AssertionError("C0 fixture missing");
    }
    static byte[] withUid(byte[] uid){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<INFO.length-2;){int tag=INFO[i++]&255,n=INFO[i++]&255;
            if(tag==0xC1){if(uid!=null){out.write(tag);out.write(uid.length);out.write(uid,0,uid.length);}}
            else{out.write(tag);out.write(n);out.write(INFO,i,n);}i+=n;}
        out.write(0x90);out.write(0);return out.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        PanelProtocol.parseInfo(INFO);passed("synthetic D1 exact profile");
        Fake factoryApp=new Fake();factoryApp.info=withAppId("00000001");factoryApp.run();
        eq(factoryApp.d1==2&&factoryApp.d3==476&&factoryApp.d4==1&&factoryApp.emitted("REFRESH_DONE"),"registered factory APPID passes initial and pre-write checks");
        passed("00000001 APPID follows full upload and refresh path");
        Fake unknownApp=new Fake();unknownApp.info=withAppId("00000002");
        try {unknownApp.run();throw new AssertionError("unknown APPID accepted");}
        catch(IllegalArgumentException e){eq(e.getMessage().contains("C0：期望=201EA001 或 00000001，实际=00000002"),"APPID diagnostic lists both supported values");}
        eq(unknownApp.d8+unknownApp.pin+unknownApp.d3+unknownApp.d4==0,"unknown APPID stops before authentication or writes");
        passed("unknown APPID still rejected before authentication or writes");
        eq(PanelProtocol.parseInfo(withUid(PanelProtocol.hex("12345678"))).uid.equals("12345678"),"different UID is display only");
        eq(PanelProtocol.parseInfo(withUid(PanelProtocol.hex("01020304050607"))).uid.equals("01020304050607"),"no UID length restriction");
        eq(PanelProtocol.parseInfo(withUid(null)).uid.equals("未提供"),"UID field is optional");passed("different, longer and absent UID accepted without ID validation");
        rejects(()->PanelProtocol.parseInfo(PanelProtocol.hex("A007F09000")),"truncated TLV");
        byte[] duplicate=Arrays.copyOf(INFO,INFO.length+3);System.arraycopy(PanelProtocol.hex("B101029000"),0,duplicate,INFO.length-2,5);
        rejects(()->PanelProtocol.parseInfo(duplicate),"duplicate TLV");
        byte[] badScan=INFO.clone();badScan[13]=0x11;rejects(()->PanelProtocol.parseInfo(badScan),"factory scan mismatch");passed("screen profile and malformed TLV gates");
        try {PanelProtocol.parseInfo(badScan);throw new AssertionError("mismatch accepted");}
        catch(IllegalArgumentException e){eq(e.getMessage().contains("A1：期望=00120030FFFFFF，实际=00121130FFFFFF"),"scan diagnostic contains expected and actual");}
        byte[] multiple=badScan.clone();multiple[20]=3;
        try {PanelProtocol.parseInfo(multiple);throw new AssertionError("multiple mismatches accepted");}
        catch(IllegalArgumentException e){eq(e.getMessage().contains("A1：")&&e.getMessage().contains("B1：期望=02，实际=03"),"all mismatches reported together");}
        passed("field-level mismatch diagnostics");
        eq(PanelProtocol.toHex(PanelProtocol.packCodes(new byte[]{3,0,2,1,1,2,0,3},2,4)).equals("E41B"),"packing direction vector");passed("factory scan packing direction");
        Fake other=new Fake();other.info=withUid(PanelProtocol.hex("87654321"));other.run();eq(other.d3==476&&other.d4==1&&other.emitted("REFRESH_DONE"),"second screen completes with a different UID");passed("different UID follows full upload and refresh path");
        Fake mismatch=new Fake();mismatch.info=badScan;rejects(mismatch::run,"profile mismatch before authentication");eq(mismatch.d8+mismatch.pin+mismatch.d3+mismatch.d4==0,"profile rejection before mutating commands");passed("incompatible screen profile still sends only D1");
        Fake good=new Fake();good.run();eq(good.d3==476&&good.d4==1&&good.de==5&&good.pin==0,"complete packet and status cycle");eq(good.emitted("REFRESH_DONE"),"busy-to-idle event");
        ByteArrayOutputStream current=new ByteArrayOutputStream();int block=0,sub=0,blocks=0,compressed=0;
        for(byte[] p:good.uploads){eq((p[0]&255)==0xF0&&(p[1]&255)==0xD3&&p[2]==0,"D3 header");eq((p[4]&255)==p.length-5&&p.length<=257,"D3 Lc / APDU length");eq((p[5]&255)==block&&(p[6]&255)==sub,"D3 sequence");
            int count=p.length-7;current.write(p,7,count);compressed+=count;
            if(p[3]==1){eq(current.size()==(block==52?1996:2012),"final compressed block length");current.reset();block++;blocks++;sub=0;}
            else{eq(count==250,"non-final application stride remains 250");sub++;}}
        eq(blocks==53&&compressed==106620&&current.size()==0,"53 complete LZO blocks, final raw length1984");passed("476 D3 packets / 53 blocks / one D4 / busy to idle");
        Fake auth=new Fake();auth.authNeeded=true;auth.run();eq(auth.pin==1&&auth.d8==2,"one default PIN only");passed("conditional single PIN verification");
        Fake pinBad=new Fake();pinBad.authNeeded=true;pinBad.failPin=true;rejects(pinBad::run,"PIN failure");eq(pinBad.pin==1&&pinBad.d3+pinBad.d4==0,"PIN is not retried");passed("PIN failure stops without guessing");
        Fake uploadBad=new Fake();uploadBad.failD3=3;rejects(uploadBad::run,"D3 failure");eq(uploadBad.d3==3&&uploadBad.d4==0,"failed D3 not retried and no D4");passed("upload failure stops before refresh");
        Fake refreshBad=new Fake();refreshBad.failD4=true;rejects(refreshBad::run,"D4 failure");eq(refreshBad.d4==1&&refreshBad.de==0,"D4 not replayed");passed("D4 error is never replayed");
        Fake idle=new Fake();idle.noBusy=true;idle.run();eq(idle.emitted("REFRESH_UNCONFIRMED")&&!idle.emitted("REFRESH_DONE")&&idle.time>=21000,"idle only cannot claim refresh");passed("idle-only outcome remains unconfirmed");
        Fake stuck=new Fake();stuck.foreverBusy=true;rejects(stuck::run,"90s refresh deadline");eq(stuck.d4==1&&!stuck.emitted("REFRESH_DONE"),"timeout no replay");passed("busy timeout bounded without replay");
        Fake malformed=new Fake();malformed.malformedBusy=true;rejects(malformed::run,"invalid DE");eq(!malformed.emitted("REFRESH_DONE"),"invalid busy response not success");passed("invalid DE state rejected");
        Fake cancel=new Fake();cancel.cancelAtD3=10;rejects(cancel::run,"cancel during upload");eq(cancel.d3==10&&cancel.d4==0,"no write after cancel");
        Fake before=new Fake();before.cancelBeforeD4=true;rejects(before::run,"cancel before D4");eq(before.d3==476&&before.d4==0,"cancelled before trigger");passed("cancellation during upload and before D4");
        Fake invalid=new Fake();rejects(()->new PanelProtocol(invalid,invalid).write(new byte[105984],0,500),"invalid option");eq(invalid.d1==0,"options checked before APDUs");passed("invalid timing stops before any APDU");
        for(int interval:new int[]{0,10,20,50,100,200,300,400,500}){Fake f=new Fake();f.pollMs=interval;f.deLatencyMs=interval==0?7:0;f.run();eq(f.de==5&&f.emitted("REFRESH_DONE"),"busy/idle behavior at "+interval);for(int i=1;i<f.deTimes.size();i++)eq(f.deTimes.get(i)-f.deTimes.get(i-1)==interval+f.deLatencyMs,"selected DE spacing "+interval);eq(f.d4==1,"no repeated refresh at "+interval);}passed("all nine polling intervals including zero drive actual DE spacing");
        Fake latency=new Fake();latency.pollMs=200;latency.deLatencyMs=37;latency.run();for(int i=1;i<latency.deTimes.size();i++)eq(latency.deTimes.get(i)-latency.deTimes.get(i-1)==237,"response time plus requested wait");passed("poll interval is measured after each response");
        for(int interval:new int[]{-1,501,1000,2000,Integer.MAX_VALUE}){Fake f=new Fake();f.pollMs=interval;rejects(f::run,"invalid poll interval "+interval);eq(f.d1+f.d8+f.pin+f.d3+f.d4+f.de==0,"invalid poll interval sends no APDUs");}passed("negative and over-500ms polling intervals stop before any APDU");
        Fake waiting=new Fake();waiting.pollMs=500;waiting.cancelWhilePolling=true;rejects(waiting::run,"stop during poll wait");eq(waiting.de==1&&waiting.time-waiting.deTimes.get(0)==300,"poll wait remains cancellable within 100ms checks");passed("polling wait does not delay cancellation");
        Fake zeroIdle=new Fake();zeroIdle.pollMs=0;zeroIdle.deLatencyMs=37;zeroIdle.noBusy=true;zeroIdle.run();eq(zeroIdle.emitted("REFRESH_UNCONFIRMED")&&!zeroIdle.emitted("REFRESH_DONE")&&zeroIdle.time>=21000,"zero wait retains 20s minimum observation");passed("zero interval never claims success from idle-only replies");
        Fake zeroStuck=new Fake();zeroStuck.pollMs=0;zeroStuck.deLatencyMs=250;zeroStuck.foreverBusy=true;rejects(zeroStuck::run,"zero interval 90s deadline");eq(zeroStuck.d4==1&&!zeroStuck.emitted("REFRESH_DONE"),"zero wait still has bounded polling");passed("zero interval retains 90s refresh deadline without replay");
        Fake zeroCancel=new Fake();zeroCancel.pollMs=0;zeroCancel.deLatencyMs=37;zeroCancel.foreverBusy=true;zeroCancel.cancelWhilePolling=true;rejects(zeroCancel::run,"zero interval cancellation");eq(zeroCancel.de==8&&zeroCancel.d4==1,"zero wait checks cancellation between exchanges");passed("zero interval remains cancellable between DE exchanges");
        for(int charge:new int[]{0,1000,15000,30000}){
            Fake f=new Fake();f.chargeMs=charge;f.run();
            eq(f.firstUploadAt==charge+1000,"pre-charge is before image transfer: "+charge);
            eq(f.d3==476&&f.d4==1&&f.emitted("REFRESH_DONE"),"charge preserves single upload and refresh");
            eq(f.events.contains("WAIT|charging|0|"+charge)==(charge>0),"zero skips charging timer");
            if(charge>0)eq(f.events.indexOf("WAIT_DONE|charging|"+charge)<f.events.indexOf("STAGE|checking|读取并校验屏幕规格"),"charging completes before protocol APDUs");
        }passed("0–30s pre-charge executes before image upload and retains D4 timing");
        for(int power:new int[]{500,1000,2000,3000,4000,5000}){Fake f=new Fake();f.powerMs=power;f.run();eq(f.events.contains("WAIT|power|1000|"+power)&&f.d3==476&&f.d4==1,"selected power parameter drives both timer and D4 command");eq(f.time==3000,"timer reporting adds no extra protocol delay");}passed("all six existing power timings drive matching post-upload timers and D4 bytes");
        for(int charge:new int[]{-1,30001,Integer.MAX_VALUE}){Fake f=new Fake();f.chargeMs=charge;rejects(f::run,"invalid charge "+charge);eq(f.d1+f.d8+f.pin+f.d3+f.d4+f.de==0&&f.time==0,"invalid charge rejected before waiting or APDUs");}passed("pre-charge limits validated before any APDU");
        Fake chargingCancel=new Fake();chargingCancel.chargeMs=30000;chargingCancel.cancelAt=550;
        rejects(chargingCancel::run,"cancel while charging");eq(chargingCancel.time==600&&chargingCancel.d1+chargingCancel.d3+chargingCancel.d4==0&&!chargingCancel.emitted("WAIT_DONE"),"charging checks stop every 100ms and never proceeds after cancellation");passed("30s charging is cancellable within 100ms without writes");
        Path out=Paths.get(args[0]);Files.createDirectories(out);Random random=new Random(220);
        try(DataOutputStream fixtures=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("lzo-fixtures.bin"))))){
            for(int length=1;length<=2000;length++){byte[] raw=new byte[length];random.nextBytes(raw);byte[] lzo=PanelProtocol.literalLzo(raw);fixtures.writeShort(length);fixtures.writeShort(lzo.length);fixtures.write(lzo);fixtures.write(raw);}}
        byte[] codes=new byte[552*768];for(int i=0;i<codes.length;i++)codes[i]=(byte)random.nextInt(4);
        Files.write(out.resolve("codes.bin"),codes);Files.write(out.resolve("packed.bin"),PanelProtocol.packCodes(codes,552,768));
        System.out.println("JAVA_CASES="+cases+"; independent LZO and reference packing fixtures exported");
    }
}
