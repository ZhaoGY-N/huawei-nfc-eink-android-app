package org.nfcresearch.qpolllab;

public final class WorkflowStatusTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int flags=0;flags<16;flags++){
            boolean nfc=(flags&1)!=0,connection=(flags&2)!=0,panel=(flags&4)!=0,image=(flags&8)!=0;
            WorkflowStatus.Gate gate=WorkflowStatus.gate(nfc,connection,panel,image);
            check((gate==WorkflowStatus.Gate.READY)==(nfc&&connection&&panel&&image),"Never enable sending before all prerequisites");
            if(!nfc)check(gate==WorkflowStatus.Gate.NFC,"NFC off has priority");
            else if(!connection)check(gate==WorkflowStatus.Gate.CONNECTION,"Permission alone must not imply a bound service");
        }
        check(WorkflowStatus.failure("refresh","uploading","TagLostException").title.equals("图片传输中断"),"Upload interruption");
        for(String stage:new String[]{"refreshing","polling"}){
            WorkflowStatus.Advice advice=WorkflowStatus.failure("refresh",stage,"TagLostException");
            check(advice.title.equals("刷新结果待确认")&&advice.message.contains("不会自动重发"),"D4/refresh interruption must not imply success or automatic replay");
        }
        check(WorkflowStatus.failure("identity","checking","D1 SW=6401").message.contains("尚未开始传图"),"Read-only check failure");
        check(WorkflowStatus.failure("refresh","connecting","Shizuku Permission denied").title.equals("需要检查授权连接"),"Connection recovery path");
        check(WorkflowStatus.failure("refresh","uploading","恢复未确认").title.equals("需要恢复 NFC"),"Restoration failure takes priority over upload failure");
        check(!WorkflowStatus.failure("refresh","unknown","process exit 1").message.contains("尚未开始传图"),"Unknown state cannot claim nothing was transmitted");
        System.out.println("WorkflowStatusTest: PASS (all readiness combinations, stage-specific interruption, restoration priority, no replay/success assumptions)");
    }
}
