package org.nfcresearch.qpolllab;

/** Every cleanup step runs, even if an earlier hardware/service operation failed. */
final class ForegroundCleanup {
    interface Step {boolean run()throws Exception;}
    interface Failure {void report(String message);}
    private ForegroundCleanup(){}
    static boolean run(Step hookOff,Step readerOff,Step rootRelease,Failure failure){
        Step[] steps={hookOff,readerOff,rootRelease};
        String[] names={"HAL 改写关闭","前台 ReaderMode 释放","Root HAL 会话释放"};
        boolean restored=true;
        for(int i=0;i<steps.length;i++)try{
            if(!steps[i].run()){restored=false;failure.report(names[i]+"未确认，请关闭再开启 NFC");}
        }catch(Exception e){restored=false;failure.report(names[i]+"失败："+e);}
        return restored;
    }
}
