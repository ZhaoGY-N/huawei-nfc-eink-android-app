package org.nfcresearch.qpolllab;

/** Selection prerequisites, not a claim of RF or complete-refresh compatibility. */
public final class BackendOptions {
    private static final String[] TRANSPORTS={"","system","sn220","direct"};
    private BackendOptions(){}
    public static String transport(int position){if(position<0||position>=TRANSPORTS.length)throw new IllegalArgumentException("无效的寻卡选项");return TRANSPORTS[position];}
    public static int retainedSelection(int launcherUid,int position,boolean arm64,String chipConfig,boolean busy){
        return busy||blockedReason(launcherUid,position,arm64,chipConfig).isEmpty()?position:0;
    }
    public static String blockedReason(int launcherUid,int position,boolean arm64,String chipConfig){
        String transport=transport(position);
        if(transport.isEmpty())return "请选择可用的寻卡方式";
        if(!BackendPolicy.requiresRoot(transport))return "";
        if(!arm64)return "NXP HAL 兼容层只提供 arm64 版本，当前设备不满足条件";
        if(launcherUid==2000)return "当前 Shizuku 是 Shell 权限；NXP HAL 需要 Root。请以 Root 启动 Shizuku，或手动选择 Root 直连";
        if(launcherUid!=0)return "尚未确认 Root 身份；请先连接 Shizuku Root，或手动选择 Root 直连";
        if(chipConfig!=null&&chipConfig.startsWith("NXP ")&&!NxpHalPolicy.supported(chipConfig))return "当前可读固件配置不是 SN220 / SN100U；不满足本兼容层的配置条件";
        return "";
    }
    public static String launchDescription(int position){
        switch(position){
            case 0:return "通过 Shizuku UserService 与 Binder 管理会话。NFC worker 使用 UID 2000 / shell；Shizuku 以 Root 启动时，Root 仅负责准备底层再切换为 Shell。默认优先使用。";
            case 1:return "通过 Shizuku 进程与管道管理会话，绕开 UserService 启动异常。NFC worker 仍使用 UID 2000 / shell；寻卡与刷屏能力和用户服务模式相同。";
            case 2:return "无需 Shizuku，在操作开始时请求本 App 的 Root 授权，以 UID 0 调用系统 NFC 服务。仍依赖 ReaderMode 和标签回调；若 ROM 拒绝 UID 0，下面三种寻卡方式都会受影响。";
            case 3:return "前台 App 使用公开 ReaderMode 和普通应用身份通信；Root 仅管理 NXP HAL 兼容层。Q-Poll 固定使用 HAL 兼容寻卡；可另选 Root 管理通道，离开前台后停止，不自动切换或重发。";
            default:throw new IllegalArgumentException("无效的执行方式");
        }
    }
    public static String discoveryDescription(int position){
        switch(position){
            case 0:return "原组合失效后需手动重新选择，不会自动更换后端或继续发送。";
            case 1:return "调用系统厂商 QTag 接口开启 Q-Poll。依赖 ROM 与控制器支持；接口返回成功不代表已寻到标签或完成刷新。";
            case 2:return "由 Root 准备 NXP HAL 注入，改写寻卡命令与激活通知，再使用系统 ReaderMode。还需匹配实际 HAL / 符号；Root 直连组合尚未完成真机验证。";
            case 3:return "实验方式：通过系统 sendVendorNciMessage 提交 Q-Poll 命令，仍由 ReaderMode 接收标签。需要系统允许相关命令、可读收发日志及控制器应答；不绕开系统 NFC 栈。";
            default:throw new IllegalArgumentException("无效的寻卡选项");
        }
    }
}
