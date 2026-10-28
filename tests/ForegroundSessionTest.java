package org.nfcresearch.qpolllab;

import java.util.*;
public final class ForegroundSessionTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        HalLease lease=new HalLease(0);lease.command(HalLease.HEARTBEAT,4000);check(!lease.disable(8000),"Heartbeat keeps active owner alive");
        check(lease.disable(9000),"Lost owner disables HAL at timeout");lease.command(HalLease.HEARTBEAT,9001);check(lease.disable(9002),"Late heartbeat cannot revive expired lease");
        for(int command:new int[]{HalLease.DISABLE,HalLease.CANCEL,-1,99}){
            lease=new HalLease(0);lease.command(command,100);lease.command(HalLease.HEARTBEAT,200);
            check(lease.disable(201)&&!lease.released(),"Disable/EOF/bad control must await ReaderMode release");
            lease.command(HalLease.RELEASE,300);check(lease.released(),"Explicit ReaderMode release ends root ownership");
        }
        for(int failing=-1;failing<3;failing++){
            final int index=failing;List<Integer> order=new ArrayList<>();List<String> failures=new ArrayList<>();
            ForegroundCleanup.Step[] steps=new ForegroundCleanup.Step[3];
            for(int i=0;i<3;i++){final int step=i;steps[i]=()->{order.add(step);if(index==step)throw new java.io.IOException("disconnected");return true;};}
            boolean result=ForegroundCleanup.run(steps[0],steps[1],steps[2],failures::add);
            check(order.equals(Arrays.asList(0,1,2)),"Hook off precedes ReaderMode off; Root ownership released last even after failure");
            check(result==(failing<0)&&failures.size()==(failing<0?0:1),"Cleanup failure never reports restored");
        }
        List<String> failures=new ArrayList<>();check(!ForegroundCleanup.run(()->false,()->true,()->true,failures::add)&&failures.size()==1,"Missing ACK does not suppress later cleanup or claim restoration");
        System.out.println("ForegroundSessionTest: PASS (owner loss, irreversible shutdown, malformed commands, ordered cleanup, failure isolation and missing acknowledgements)");
    }
}
