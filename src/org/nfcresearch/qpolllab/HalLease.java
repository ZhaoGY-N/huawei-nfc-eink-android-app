package org.nfcresearch.qpolllab;

/** One-way shutdown: a late heartbeat cannot reopen an abandoned HAL session. */
final class HalLease {
    static final int HEARTBEAT=1,DISABLE=2,RELEASE=3,CANCEL=0;
    private long last;
    private boolean disabling,released;
    HalLease(long now){last=now;}
    synchronized void command(int command,long now){
        if(command==HEARTBEAT){if(!disable(now))last=now;}
        else if(command==DISABLE)disabling=true;
        else if(command==RELEASE){disabling=true;released=true;}
        else disabling=true;
    }
    synchronized boolean disable(long now){if(now-last>=SessionWire.OWNER_TIMEOUT_MS)disabling=true;return disabling;}
    synchronized boolean released(){return released;}
}
