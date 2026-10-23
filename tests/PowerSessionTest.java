package org.nfcresearch.qpolllab;

import java.util.ArrayList;
import java.util.List;

public final class PowerSessionTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static final class Clock implements PowerSession.Control {
        long time,cancelAt=Long.MAX_VALUE,rejectAt=Long.MAX_VALUE;
        int sleeps;
        final List<String> events=new ArrayList<>();
        public long now(){return time;}
        public boolean cancelled(){return time>=cancelAt;}
        public void check(){if(time>=rejectAt)throw new IllegalStateException("controller rejected Q-Poll");}
        public void sleep(long ms){PowerSessionTest.check(ms>0&&ms<=100);time+=ms;sleeps++;}
        public void event(String kind,String message){events.add(kind+"|"+message);}
    }
    public static void main(String[] args)throws Exception {
        Clock complete=new Clock();
        PowerSession.hold(300,complete);
        check(complete.time==300000&&complete.events.size()==2);
        check(complete.events.get(0).startsWith("POWER_ON|")&&complete.events.get(1).contains("计时结束"));
        Clock cancelled=new Clock();cancelled.cancelAt=650;
        PowerSession.hold(300,cancelled);
        check(cancelled.time==700&&cancelled.events.get(1).contains("已停止供能"));
        Clock before=new Clock();before.cancelAt=0;
        PowerSession.hold(300,before);
        check(before.sleeps==0&&before.events.isEmpty());
        Clock rejected=new Clock();rejected.rejectAt=400;
        try{PowerSession.hold(300,rejected);throw new AssertionError("ignored controller rejection");}
        catch(IllegalStateException expected){check(rejected.time==400&&rejected.events.size()==1);}
        Clock invalid=new Clock();
        for(int seconds:new int[]{0,-1,301}) {
            try{PowerSession.hold(seconds,invalid);throw new AssertionError("accepted invalid duration");}
            catch(IllegalArgumentException expected){}
        }
        check(invalid.events.isEmpty()&&invalid.sleeps==0);
        System.out.println("Power-only cancellation, timeout and controller rejection tests passed");
    }
}
