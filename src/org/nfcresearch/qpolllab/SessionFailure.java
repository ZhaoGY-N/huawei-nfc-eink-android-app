package org.nfcresearch.qpolllab;

/** Keeps the diagnostic cause when a process-exit summary arrives later. */
public final class SessionFailure {
    private String reason="",exit="";
    public void clear(){reason="";exit="";}
    public boolean present(){return !reason.isEmpty()||!exit.isEmpty();}
    public boolean accept(String kind,String message){
        if(kind.equals("PROCESS_EXIT")){exit=message;return true;}
        if(kind.equals("QTAG_REJECTED")||kind.equals("ERROR")||kind.equals("CLEANUP_ERROR")||kind.equals("RESTORE_FAILED")){
            // Compatibility with an older Shizuku service during an upgrade.
            if(message.startsWith("后端进程退出码=")){exit=message.substring("后端进程退出码=".length());return true;}
            if(reason.isEmpty())reason=message;
            else if(!reason.contains(message))reason+="\n"+message;
            return true;
        }
        return false;
    }
    public String message(){
        if(exit.isEmpty())return reason;
        return (reason.isEmpty()?"未收到具体错误原因，后端只返回退出码。":reason)+"\n后端进程退出码="+exit;
    }
}
