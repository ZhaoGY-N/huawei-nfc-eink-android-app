package org.nfcresearch.qpolllab;

/** Holds an already-enabled NFC session without any panel or tag I/O. */
public final class PowerSession {
    public interface Control {
        long now();
        boolean cancelled();
        void check() throws Exception;
        void sleep(long ms) throws Exception;
        void event(String kind,String message);
    }
    private PowerSession() {}
    public static void hold(int seconds,Control control)throws Exception {
        BackendPolicy.validateDuration("power",seconds);
        control.check();
        if(control.cancelled())return;
        long deadline=control.now()+seconds*1000L;
        control.event("POWER_ON","仅供能模式已开启；未执行屏幕读取、认证、传图或刷新");
        while(!control.cancelled()) {
            control.check();
            long remaining=deadline-control.now();
            if(remaining<=0)break;
            control.sleep(Math.min(100,remaining));
        }
        control.event("POWER_DONE",control.cancelled()?"已停止供能，正在恢复 NFC":"本轮供能计时结束，正在恢复 NFC");
    }
}
