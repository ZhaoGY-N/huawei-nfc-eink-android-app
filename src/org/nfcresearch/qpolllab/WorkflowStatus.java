package org.nfcresearch.qpolllab;

/** User-facing prerequisites and stage-specific advice; never initiates an NFC operation. */
public final class WorkflowStatus {
    public enum Gate { NFC, CONNECTION, PANEL, IMAGE, READY }
    public static Gate gate(boolean nfc,boolean connected,boolean panel,boolean image){
        return !nfc?Gate.NFC:!connected?Gate.CONNECTION:!panel?Gate.PANEL:!image?Gate.IMAGE:Gate.READY;
    }
    public static final class Advice {
        public final String title,message;
        Advice(String title,String message){this.title=title;this.message=message;}
    }
    public static Advice failure(String mode,String stage,String cause){
        String text=cause==null?"":cause;
        if(text.contains("恢复")||text.contains("ReaderMode 失败")||text.contains("关闭 QTag 失败"))
            return new Advice("需要恢复 NFC","NFC 恢复尚未确认。请先关闭再开启系统 NFC，再检查连接。若本轮已传图，请查看屏幕画面。");
        if(stage.equals("refreshing")||stage.equals("polling"))
            return new Advice("刷新结果待确认","已进入启动或等待刷新阶段。请检查实物画面和贴合位置，再决定是否重新发送；本轮不会自动重发。");
        if(stage.equals("uploading"))
            return new Advice("图片传输中断","图像数据未完整确认。请检查贴合位置与屏幕状态，再决定是否重新发送；原始错误信息显示在下方。");
        if(text.contains("Shizuku")||text.contains("授权")||text.contains("Permission")||text.contains("SecurityException"))
            return new Advice("需要检查授权连接","请在设备页检查当前执行方式和授权服务。连接恢复后，请重新点击操作；不会自动继续发送。");
        if(stage.equals("checking")||stage.equals("authenticating"))
            return new Advice("屏幕检查未通过","本轮尚未开始传图。请核对屏幕规格、贴合位置与供能；查看日志中的检查结果后再操作。");
        return new Advice(mode.equals("power")?"供能会话中断":"本轮操作未完成","请先检查授权、NFC 开关与贴合位置。原始错误信息显示在下方；本轮不会自动重试或重发。");
    }
}
