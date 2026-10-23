package org.nfcresearch.qpolllab;
import java.io.*;
import java.util.Arrays;

public final class SessionWireTest {
    interface Action{void run()throws Exception;}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("unsafe task accepted");}catch(IOException|IllegalArgumentException|SecurityException expected){}}
    public static void main(String[] args)throws Exception {
        byte[] pixels=new byte[105984];new java.util.Random(28).nextBytes(pixels);
        SessionWire.Job input=new SessionWire.Job("refresh",10,"{\"label\":\"单引号'与换行\\n\"}",pixels);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();SessionWire.write(bytes,input);
        SessionWire.Job output=SessionWire.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(output.mode.equals(input.mode)&&output.seconds==10&&output.json.equals(input.json)&&Arrays.equals(output.image,pixels));
        for(String mode:new String[]{"inspect","standard","qtag","identity","power"}){
            bytes.reset();SessionWire.write(bytes,new SessionWire.Job(mode,mode.equals("power")?300:60,"{}",new byte[0]));
            check(SessionWire.read(new ByteArrayInputStream(bytes.toByteArray())).mode.equals(mode));
            reject(()->SessionWire.validate(new SessionWire.Job(mode,10,"{}",new byte[1])));
        }
        reject(()->SessionWire.validate(new SessionWire.Job("refresh",10,"{}",new byte[1])));
        reject(()->SessionWire.validate(new SessionWire.Job("refresh",10,"{}",new byte[105985])));
        reject(()->SessionWire.validate(new SessionWire.Job("identity",10,new String(new char[8193]).replace('\0','x'),new byte[0])));
        reject(()->SessionWire.validate(new SessionWire.Job("identity",10,new String(new char[3000]).replace('\0','测'),new byte[0])));
        reject(()->SessionWire.validate(new SessionWire.Job("power",301,"{}",new byte[0])));
        reject(()->SessionWire.validate(new SessionWire.Job("identity",61,"{}",new byte[0])));
        reject(()->SessionWire.validate(new SessionWire.Job("su -c bad",10,"{}",new byte[0])));
        reject(()->SessionWire.read(new ByteArrayInputStream(new byte[4])));
        bytes.reset();SessionWire.write(bytes,input);byte[] complete=bytes.toByteArray();
        reject(()->SessionWire.read(new ByteArrayInputStream(Arrays.copyOf(complete,complete.length-1))));
        for(int bad:new int[]{-1,Integer.MAX_VALUE}){
            ByteArrayOutputStream malformed=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(malformed);
            d.writeInt(0x51504231);d.writeUTF("identity");d.writeInt(10);d.writeInt(bad);
            reject(()->SessionWire.read(new ByteArrayInputStream(malformed.toByteArray())));
        }
        SessionWire.Lease owner=new SessionWire.Lease(0);
        check(!owner.cancelled(4999)&&owner.cancelled(5000));
        owner.control(1,5001);check(owner.cancelled(5001));
        owner=new SessionWire.Lease(0);owner.control(1,5000);check(owner.cancelled(5000));
        owner=new SessionWire.Lease(0);owner.control(1,4000);check(!owner.cancelled(8999)&&owner.cancelled(9000));
        for(int stop:new int[]{0,-1,2}){
            owner=new SessionWire.Lease(0);owner.control(stop,1);check(owner.cancelled(1));owner.control(1,2);check(owner.cancelled(2));
        }
        System.out.println("Process task bounds, image integrity, owner EOF/stop/heartbeat tests passed");
    }
}
