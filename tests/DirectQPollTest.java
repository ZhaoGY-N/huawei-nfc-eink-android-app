package org.nfcresearch.qpolllab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DirectQPollTest {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static Exception fails(Action action,String message) throws Exception {
        try {action.run();throw new AssertionError(message);} catch(IOException|InterruptedException expected) {return expected;}
    }
    private static final class Rig {
        final AtomicBoolean cancelled=new AtomicBoolean();
        final List<String> events=new ArrayList<>();
        final DirectQPoll session;
        int discoverStatus, deactStatus, hostStatus, rawCalls;
        boolean noTx, noResponse, noNormal, malformed, responseBeforeTx, wrongOid, interleaved;
        boolean failReader, failRelease, cancelAfterDeactivate, async;
        Rig() {
            session=new DirectQPoll(this::send,this::reader,cancelled::get,
                    (kind,message)->events.add(kind),80);
        }
        void reader(int flags) throws Exception {
            events.add("reader:"+flags);
            if(flags==0 && failRelease)throw new IOException("release failure");
            if(flags!=0 && failReader)throw new IOException("reader failure");
            if(!noNormal)deliver(()->{
                session.observe(false,"41030100"); // stale response must not confirm discovery
                session.observe(true,"21 03 03 01 00 01");
                session.observe(false,"41 03 01 00");
            });
        }
        int send(int gid,int oid,byte[] payload) {
            check(gid==1,"RF management GID must be 1");
            String packet=oid==6?"21060100":"210303017101";
            check(oid==6?java.util.Arrays.equals(payload,new byte[]{0}):
                    oid==3&&java.util.Arrays.equals(payload,new byte[]{1,0x71,1}),"wrong command bytes");
            check(oid!=3 || session.acceptsTags(),"Q-Poll callback gate must be ready");
            rawCalls++;events.add(packet);
            int status=oid==6?deactStatus:discoverStatus;
            deliver(()->{
                String response=String.format(java.util.Locale.ROOT,"41%02X01%02X",wrongOid?7:oid,status);
                if(responseBeforeTx)session.observe(false,response);
                if(!noTx)session.observe(true,packet);
                if(interleaved)session.observe(true,"210303010001");
                if(!noResponse&&!responseBeforeTx)session.observe(false,malformed?"41030200":response);
                if(cancelAfterDeactivate&&oid==6)cancelled.set(true);
            });
            return hostStatus;
        }
        void deliver(Runnable action) {
            if(async)new Thread(action).start();else action.run();
        }
    }
    public static void main(String[] args) throws Exception {
        Rig normal=new Rig();normal.async=true;
        normal.session.start();
        check(normal.session.acceptsTags(),"tags allowed after Q-Poll starts");
        normal.cancelled.set(true); // Stop must not prevent cleanup commands.
        normal.session.close();
        check(!normal.session.acceptsTags(),"cleanup closes callback gate");
        check(normal.events.indexOf("reader:129")<normal.events.indexOf("21060100")
                &&normal.events.indexOf("21060100")<normal.events.indexOf("210303017101"),"startup order");
        check(normal.rawCalls==3&&normal.events.contains("reader:0"),"deactivate, discover, deactivate and release");
        normal.session.close();check(normal.rawCalls==3,"cleanup is idempotent");
        normal.cancelled.set(false);normal.session.start();normal.session.close();
        check(normal.rawCalls==6,"reconnect starts a fresh bounded session");

        Rig early=new Rig();early.cancelled.set(true);
        fails(early.session::start,"cancelled start accepted");early.session.close();
        check(early.events.isEmpty(),"cancel before start must not touch NFC");
        Rig midway=new Rig();midway.cancelAfterDeactivate=true;
        fails(midway.session::start,"cancel between commands ignored");
        check(!midway.events.contains("210303017101"),"no discovery after cancellation");
        midway.session.close();check(midway.events.contains("reader:0"),"cancel must still release ReaderMode");

        Rig reject=new Rig();reject.discoverStatus=0xA0;
        Exception rejected=fails(reject.session::start,"controller rejection accepted");
        check(rejected.getMessage().contains("A0"),"preserve controller status");
        check(!reject.events.contains("CAPABILITY"),"no capability claim on rejection");reject.session.close();
        Rig deact=new Rig();deact.deactStatus=5;
        fails(deact.session::start,"deactivation rejection accepted");
        check(!deact.events.contains("210303017101"),"must not discover before successful deactivation");
        fails(deact.session::close,"uncertain deactivation must fail cleanup");
        check(deact.events.contains("reader:0"),"release ReaderMode even if raw cleanup failed");

        // Host success, stale responses, wrong opcodes and malformed packets are insufficient.
        for(int fault=0;fault<7;fault++) {
            Rig rig=new Rig();
            if(fault==0)rig.noTx=true;
            if(fault==1)rig.noResponse=true;
            if(fault==2)rig.malformed=true;
            if(fault==3)rig.responseBeforeTx=true;
            if(fault==4)rig.wrongOid=true;
            if(fault==5)rig.interleaved=true;
            if(fault==6)rig.hostStatus=3;
            fails(rig.session::start,"invalid exchange accepted: "+fault);
            check(!rig.events.contains("CAPABILITY"),"no capability claim without matched wire success");
            rig.noTx=rig.noResponse=rig.malformed=rig.responseBeforeTx=rig.wrongOid=rig.interleaved=false;
            rig.hostStatus=0;rig.session.close();
        }
        Rig missingLogs=new Rig();missingLogs.session.logsUnavailable("unavailable");
        fails(missingLogs.session::start,"missing log stream accepted");
        check(missingLogs.rawCalls==0&&missingLogs.events.isEmpty(),"log preflight must precede NFC changes");
        Rig noFramework=new Rig();noFramework.noNormal=true;
        fails(noFramework.session::start,"missing framework discovery accepted");
        check(noFramework.rawCalls==0,"must not override unfinished framework discovery");
        fails(noFramework.session::close,"restore without normal discovery claimed");
        Rig release=new Rig();release.session.start();release.failRelease=true;
        fails(release.session::close,"failed reader release accepted");
        check(!release.session.acceptsTags(),"failed cleanup must still close callback gate");
        Rig lostLogs=new Rig();lostLogs.session.start();lostLogs.noResponse=true;
        lostLogs.session.logsUnavailable("stream closed during scan");
        fails(lostLogs.session::close,"missing cleanup evidence accepted");
        check(lostLogs.rawCalls==3&&lostLogs.events.contains("reader:0"),
                "log failure must not prevent best-effort deactivation and reader release");
        Rig reader=new Rig();reader.failReader=true;
        fails(reader.session::start,"reader failure accepted");reader.session.close();
        check(reader.rawCalls==0&&reader.events.contains("reader:0"),"reader failure cleanup");
        System.out.println("Direct Q-Poll command/response, cancellation, reconnect and restoration tests passed");
    }
}
