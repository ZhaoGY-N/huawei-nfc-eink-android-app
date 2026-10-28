package org.nfcresearch.qpolllab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;
import android.os.Parcel;

public final class MainActivity extends Activity {
    private final Handler main=new Handler();
    private final ArrayList<View> idleControls=new ArrayList<>();
    private final StringBuilder transcript=new StringBuilder();
    private final int[] durations={5,10,30,60},powerTimes={500,1000,2000,3000,4000,5000},pollTimes={0,10,20,50,100,200,300,400,500},blockSizes={1,2,4,8,16};
    private TextView state,detail,log,imageInfo,thresholdLabel,chargeTiming,refreshTiming;
    private PixelPreview preview,editPreview;
    private TextView editInfo,failureInfo,deviceFeedback,editorLocked;
    private Button deviceStop,leaveCalibrationEdit;
    private String lastSessionMode="";
    private ProgressBar progress;
    private Button refresh,stop,powerSupply;
    private TextView powerInfo;
    private String activeMode="";
    private boolean powerTagActivated;
    private long powerDeadline;
    private Button rotateImage,cropImage,restoreImage;
    private Spinner duration,fit,power,poll,blocks,panelType,charge;
    private CheckBox dither,binary;
    private SeekBar threshold;
    private Bitmap source,originalSource;
    private CropWindow appliedCrop;
    private int cropRotation;
    private boolean cropped,editingCrop;
    private TestImageMode imageMode=TestImageMode.FOUR_COLOR;
    private RecentImageStore recentImages;
    private AlertDialog recentDialog;
    private CalibrationUi calibration;
    private ImageAdjustmentUi adjustments;
    private PanelImage.Prepared prepared;
    private int rotation,revision;
    private String imageName="内置四色测试图";
    private volatile boolean busy,preparing,resumed,destroyed;
    private volatile File stopFile;
    private Spinner launchMode,discoveryBackend,halManager;
    private LinearLayout halManagerBody;
    private volatile ForegroundNfcSession foregroundSession;
    private TextView authorizationInfo;
    private TextView launchExplanation,discoveryExplanation,discoveryRestriction;
    private BackendChoiceAdapter discoveryAdapter;
    private boolean arm64Device,updatingBackendChoices;
    private String readableChipConfig,backendChoiceSignature="",backendChoiceNotice="";
    private int bridgeUid=-1;
    private final LinearLayout[] pageBodies=new LinearLayout[4];
    private final ScrollView[] pageScrolls=new ScrollView[4];
    private final Button[] tabs=new Button[4];
    private int activePage;
    private final LinearLayout[] pageFrames=new LinearLayout[4],editorBodies=new LinearLayout[3];
    private final Button[] editorTabs=new Button[3];
    private TextView readiness,setupInfo,thresholdHint,ditherHint,sessionSteps;
    private Button readinessAction,setupDone,resultAction,leaveCalibration;
    private boolean initializing=true,restoringDraft,stopping;
    private String sessionStage="connecting",lastAdvice="";
    private ImageDraftStore draftStore;
    private final java.util.concurrent.ExecutorService draftWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int draftRevision;
    private EditorSnapshot calibrationReturn;
    private Bitmap savedDraftOriginal,savedDraftCurrent;
    private byte[] savedOriginalPng,savedCurrentPng;
    private final Runnable saveDraftLater=()->saveDraft();
    private final android.content.BroadcastReceiver nfcStateReceiver=new android.content.BroadcastReceiver(){@Override public void onReceive(android.content.Context context,Intent intent){controls();}};
    private TextView chipInfo,capabilityInfo;
    private android.app.Dialog previewDialog;
    private volatile android.os.IBinder bridgeBinder;
    private boolean bridgeBinding,permissionPending;
    private long bridgeBindingStarted;
    private final Runnable bridgeTimeout=()->{if(!destroyed&&bridgeBinding){bridgeBinding=false;authorizationInfo.setText("已授权，用户服务启动超时；可选择 Shizuku 进程兼容");append("Shizuku 用户服务超过 10 秒未连接；可手动切换进程兼容，不自动重试 NFC 操作。");}};
    private final Shizuku.UserServiceArgs bridgeArgs=new Shizuku.UserServiceArgs(new android.content.ComponentName("org.nfcresearch.qpolllab","org.nfcresearch.qpolllab.ShellBridge")).tag("nfc-shell").version(33).daemon(false).processNameSuffix("nfc_shell");
    private final Shizuku.UserServiceArgs halBridgeArgs=new Shizuku.UserServiceArgs(new android.content.ComponentName("org.nfcresearch.qpolllab","org.nfcresearch.qpolllab.RootHalBridge")).tag("nfc-foreground-hal").version(33).daemon(false).processNameSuffix("nfc_hal");
    private final android.content.ServiceConnection bridgeConnection=new android.content.ServiceConnection(){
        public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
            if(destroyed||controlLaunch()!=0||!currentBridgeName(name))return;
            main.removeCallbacks(bridgeTimeout);
            bridgeBinding=false;
            Parcel request=Parcel.obtain(),reply=Parcel.obtain();
            try{request.writeInterfaceToken(selectedLaunch()==3?RootHalBridge.INTERFACE:"org.nfcresearch.qpolllab.NfcSession");if(!binder.transact(4,request,reply,0))throw new IOException("请重新连接新版后端");reply.readException();bridgeUid=reply.readInt();BackendPolicy.validate(bridgeUid,"inspect","system");bridgeBinder=binder;
                String message="Shizuku "+(bridgeUid==0?"Root":"Shell")+" · UID="+bridgeUid+(selectedLaunch()==3?"；NFC 通信使用前台 App 身份":"；NFC 通信使用 Shell 身份");authorizationInfo.setText(message);append(message);
            }catch(Exception e){bridgeBinder=null;bridgeUid=-1;authorizationInfo.setText("连接失败，请停止并重新启动 Shizuku 后重连");append("后端身份检查失败："+e);}finally{request.recycle();reply.recycle();controls();}
        }
        public void onServiceDisconnected(android.content.ComponentName name){if(destroyed||controlLaunch()!=0||!currentBridgeName(name))return;bridgeBinding=false;bridgeBinder=null;bridgeUid=-1;authorizationInfo.setText("Shizuku 未连接");append("Shizuku 后端已断开。");if(busy)requestStop("授权服务断开");controls();}
    };
    private final Shizuku.OnBinderReceivedListener binderReceived=()->connectShizuku(false);
    private final Shizuku.OnBinderDeadListener binderDead=()->{if(destroyed||controlLaunch()==2)return;bridgeBinding=false;permissionPending=false;bridgeBinder=null;bridgeUid=-1;authorizationInfo.setText("Shizuku 服务未运行，启动后自动连接");if(busy)requestStop("Shizuku 服务断开");controls();};
    private final Shizuku.OnRequestPermissionResultListener permissionResult=(code,result)->{if(code==28&&!destroyed){permissionPending=false;if(result==android.content.pm.PackageManager.PERMISSION_GRANTED)connectShizuku(false);else{authorizationInfo.setText("未获授权，请点击 Shizuku 授权 / 连接");append("Shizuku 授权未允许，可在 Shizuku 中重新授权。");controls();}}};
    private void connectShizuku(){connectShizuku(true);}
    private void connectShizuku(boolean requestPermission){if(destroyed)return;try{
        if(controlLaunch()==2){authorizationInfo.setText(selectedLaunch()==3?"前台 App 通信 · 操作时请求 Root 管理 HAL；无需 Shizuku":"Root 直连（旧版）· 执行时请求 Root 授权，无需启动 Shizuku");return;}
        if(!Shizuku.pingBinder()){authorizationInfo.setText("Shizuku 服务未运行，启动后自动连接");if(requestPermission)append("请先在 Shizuku 中启动服务，已授权后会自动连接。");return;}
        if(Shizuku.isPreV11()){authorizationInfo.setText("请升级 Shizuku");return;}
        if(Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            bridgeBinder=null;bridgeUid=-1;authorizationInfo.setText("未获授权，请点击 Shizuku 授权 / 连接");
            if(requestPermission&&!permissionPending){permissionPending=true;Shizuku.requestPermission(28);}return;
        }
        permissionPending=false;
        if(controlLaunch()==1){bridgeUid=Shizuku.getUid();BackendPolicy.validate(bridgeUid,"inspect","system");authorizationInfo.setText("Shizuku 进程兼容 · "+(bridgeUid==0?"Root":"Shell")+" UID="+bridgeUid);return;}
        if(bridgeBinder!=null&&bridgeBinder.isBinderAlive())return;
        if(bridgeBinding&&SystemClock.elapsedRealtime()-bridgeBindingStarted<10000)return;
        bridgeBinding=true;bridgeBindingStarted=SystemClock.elapsedRealtime();authorizationInfo.setText("已授权，正在连接 Shizuku…");
        main.removeCallbacks(bridgeTimeout);main.postDelayed(bridgeTimeout,10000);
        Shizuku.bindUserService(selectedLaunch()==3?halBridgeArgs:bridgeArgs,bridgeConnection);
    }catch(Exception e){bridgeBinding=false;permissionPending=false;authorizationInfo.setText("Shizuku 连接失败，请重试");append("Shizuku 连接失败："+e);}finally{controls();}}
    private boolean scanning,terminal;
    private final SessionFailure failure=new SessionFailure();
    private long scanDeadline;
    private String waitPhase="";
    private long waitStarted;
    private int waitTotal;
    private final Runnable tick=new Runnable(){public void run(){
        if(busy&&activeMode.equals("power")&&powerDeadline>0){showPowerStatus();main.postDelayed(this,250);}
        else if(busy&&scanning){detail.setText("寻找兼容墨水屏 · 最长还剩 "+Math.max(0,(scanDeadline-SystemClock.elapsedRealtime()+999)/1000)+" 秒；保持贴合。");main.postDelayed(this,250);}
        else if(busy&&!waitPhase.isEmpty()){
            long elapsed=SystemClock.elapsedRealtime()-waitStarted;
            if(waitPhase.equals("power")&&elapsed>=waitTotal){showWait(waitTotal,true);waitPhase="";}
            else{showWait(elapsed,false);main.postDelayed(this,100);}
        }
    }};
    private void showPowerStatus(){
        long remaining=Math.max(0,(powerDeadline-SystemClock.elapsedRealtime()+999)/1000);
        String message=(powerTagActivated?"已激活过标签":"等待系统激活标签")+" · 剩余 "+remaining+" 秒\n移动线圈并观察万用表；本页不测量电压或功率。";
        detail.setText(message);powerInfo.setText(message);
    }
    private void showWait(long elapsed,boolean finished){
        int value=(int)Math.min(waitTotal,Math.max(0,elapsed));
        if(busy&&!terminal&&!stopping&&!failure.present()){
            progress.setIndeterminate(false);progress.setMax((int)Math.max(1,waitTotal));progress.setProgress(value);progress.setVisibility(View.VISIBLE);
            chargeTiming.setVisibility(waitPhase.equals("charging")?View.VISIBLE:View.GONE);refreshTiming.setVisibility(waitPhase.equals("power")?View.VISIBLE:View.GONE);
        }
        if(waitPhase.equals("charging")){
            chargeTiming.setText(finished?"发送前充能已完成 · "+seconds(elapsed)+" 秒":"发送前充能 · 已充能 "+seconds(value)+" / "+seconds(waitTotal)+" 秒 · 剩余 "+seconds(waitTotal-value)+" 秒");
        }else{
            refreshTiming.setText(finished?"发送后上电等待计时结束 · "+seconds(waitTotal)+" 秒":"发送后上电等待 · 已等待 "+seconds(value)+" / "+seconds(waitTotal)+" 秒 · 剩余 "+seconds(waitTotal-value)+" 秒");
        }
    }
    private static String seconds(long ms){return String.format(Locale.ROOT,"%.1f",Math.max(0,ms)/1000.0);}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        for(String abi:android.os.Build.SUPPORTED_ABIS)arm64Device|=abi.equals("arm64-v8a");
        recentImages=new RecentImageStore(new File(getFilesDir(),"recent-images"));
        draftStore=new ImageDraftStore(new File(getFilesDir(),"editor-draft.bin"));
        LinearLayout root=column();root.setBackgroundColor(0xfff4f7f5);root.setPadding(dp(12),dp(6),dp(12),0);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title=text("墨水屏",22,0xff142f2a);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button logsButton=button("日志");logsButton.setOnClickListener(v->showLogs());header.addView(logsButton,AppUi.params(this,-2,-2,0));root.addView(header);
        LinearLayout card=surface();
        state=text("检查连接状态",17,0xff176b61);state.setTypeface(null,Typeface.BOLD);state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(state);
        detail=text("完成设备设置后即可刷图。",12,0xff52635d);detail.setMaxLines(Integer.MAX_VALUE);card.addView(detail);
        sessionSteps=text("",11,0xff52635d);sessionSteps.setVisibility(View.GONE);card.addView(sessionSteps);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setVisibility(View.GONE);card.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        chargeTiming=text("",12,0xff52635d);card.addView(chargeTiming);chargeTiming.setVisibility(View.GONE);
        refreshTiming=text("",12,0xff52635d);card.addView(refreshTiming);refreshTiming.setVisibility(View.GONE);
        // Timing phases share the single visible progress bar.
        failureInfo=text("",13,0xff9b3526);failureInfo.setTypeface(Typeface.MONOSPACE);failureInfo.setTextIsSelectable(true);failureInfo.setVisibility(View.GONE);card.addView(failureInfo);
        resultAction=button("排查建议 / 日志");resultAction.setVisibility(View.GONE);resultAction.setOnClickListener(v->showAdvice());card.addView(resultAction);
        stop=button("停止并恢复 NFC");stop.setVisibility(View.GONE);stop.setOnClickListener(v->requestStop("用户停止"));
        android.widget.FrameLayout host=new android.widget.FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<4;i++){
            pageFrames[i]=column();host.addView(pageFrames[i],new android.widget.FrameLayout.LayoutParams(-1,-1));
            if(i!=3){pageScrolls[i]=new ScrollView(this);pageScrolls[i].setFillViewport(true);pageBodies[i]=column();pageBodies[i].setPadding(0,dp(8),0,dp(20));pageScrolls[i].addView(pageBodies[i]);pageFrames[i].addView(pageScrolls[i],new LinearLayout.LayoutParams(-1,-1));}
        }
        LinearLayout nav=new LinearLayout(this);nav.setPadding(0,dp(6),0,dp(8));root.addView(nav);
        for(int index:new int[]{1,3,0,2}){tabs[index]=button(index==1?"发送":index==3?"编辑":index==0?"设备":"设置");tabs[index].setOnClickListener(v->selectPage(index));nav.addView(tabs[index],weighted());}
        Shizuku.addRequestPermissionResultListener(permissionResult);

        LinearLayout page=pageBodies[0];section(page,"设备设置");
        deviceFeedback=text("",13,0xff52635d);deviceFeedback.setTextIsSelectable(true);deviceFeedback.setVisibility(View.GONE);page.addView(deviceFeedback);
        deviceStop=button("停止当前操作并恢复 NFC");deviceStop.setVisibility(View.GONE);deviceStop.setOnClickListener(v->requestStop("用户停止"));page.addView(deviceStop);
        LinearLayout setup=surface();setupInfo=text("",14,0xff263e35);setup.addView(setupInfo);
        setupDone=action("完成设置，前往发送",v->{getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putBoolean("setupComplete",true).apply();selectPage(1);});primary(setupDone);setup.addView(setupDone);page.addView(setup);
        section(page,"授权连接");authorizationInfo=text("正在检查授权连接…",14,0xff52635d);page.addView(authorizationInfo);
        page.addView(action("Shizuku 授权 / 连接",v->connectShizuku()));
        page.addView(text("首次使用需在 Shizuku 中启动服务并允许授权；以后自动连接。Root 直连在执行时请求授权。",12,0xff68766e));
        section(page,"屏幕类型");
        panelType=spinner(new String[]{"请选择屏幕类型","A0 · 华为 552×768 四色","A1 · 华为 552×768 四色","A0X · 修正 A0 左右半屏互换"});
        String savedPanel=getSharedPreferences("refresh-settings",MODE_PRIVATE).getString("panelType","");
        panelType.setSelection(savedPanel.equals("A0")?1:savedPanel.equals("A1")?2:savedPanel.equals("A0X")?3:0);page.addView(panelType);
        page.addView(text("三种类型按实物并列选择；更换屏幕后请核对。屏幕检查无法自动识别类型。",12,0xff68766e));
        page.addView(action("屏幕类型说明",v->new AlertDialog.Builder(this).setTitle("选择屏幕类型").setMessage("A0、A1：根据屏幕标识或可靠的来源信息选择，不能根据 D1 或 UID 自动判断。\n\nA0X：适用于 A0 左右半屏顺序相反的屏幕；不用于所有镜像或压缩问题。\n\n三者均要求实物与板内原厂驱动对应。无法确认时，可先执行只读检查并核对屏幕来源。").setPositiveButton("知道了",null).show()));
        LinearLayout diagnostics=fold(page,"检测工具 · 芯片 / 寻卡 / 仅供能",false);
        diagnostics.addView(text(android.os.Build.MANUFACTURER+" · "+android.os.Build.MODEL,14,0xff52635d));
        chipInfo=text("芯片：尚未检查",14,0xff263e35);diagnostics.addView(chipInfo);
        capabilityInfo=text("Q-Poll：尚未测试",14,0xff263e35);diagnostics.addView(capabilityInfo);
        diagnostics.addView(action("检查芯片 / NFC",v->start("inspect")));
        diagnostics.addView(action("只读检查屏幕",v->start("identity")));
        diagnostics.addView(text("只读检查核对屏幕规格与原厂配置，不传图、不刷新。芯片配置、寻卡成功均不能单独证明完整刷新兼容。",12,0xff68766e));
        duration=spinner(new String[]{"寻卡 5 秒","寻卡 10 秒","寻卡 30 秒","寻卡 60 秒"});duration.setSelection(1);diagnostics.addView(duration);
        diagnostics.addView(action("测试 Q-Poll 能力",v->start("qtag")));diagnostics.addView(action("普通 NFC-A 对照",v->start("standard")));
        section(diagnostics,"NFC 仅供能 · 寻找耦合位置");powerSupply=button("开启 NFC 供能");powerSupply.setOnClickListener(v->{if(busy&&activeMode.equals("power"))requestStop("用户关闭供能");else start("power");});diagnostics.addView(powerSupply);
        powerInfo=text("未开启供能",14,0xff52635d);diagnostics.addView(powerInfo);
        diagnostics.addView(text("不传图、不刷新。开启后移动线圈，配合万用表比较位置；寻卡期间供能可能间歇。每轮最长 5 分钟，离开应用自动停止。",12,0xff68766e));

        LinearLayout send=pageBodies[1];send.addView(card);send.addView(stop);
        LinearLayout previewCard=surface();send.addView(previewCard);
        preview=new PixelPreview(this);preview.setContentDescription("最终发送像素预览，点击放大后可缩放");preview.setOnClickListener(v->showPixels());previewCard.addView(preview,new LinearLayout.LayoutParams(-1,dp(360)));
        imageInfo=text("正在生成预览…",12,0xff52635d);imageInfo.setMaxLines(2);imageInfo.setOnClickListener(v->showImageSettings());previewCard.addView(imageInfo);
        leaveCalibration=action("退出校色测试 · 恢复编辑",v->leaveCalibrationChart());leaveCalibration.setVisibility(View.GONE);previewCard.addView(leaveCalibration);
        refresh=action("发送到墨水屏",v->confirmRefresh());primary(refresh);send.addView(refresh);
        readiness=text("",12,0xff52635d);send.addView(readiness);
        readinessAction=action("完成设备设置",v->resolveReadiness());send.addView(readinessAction);
        send.addView(action("编辑图片 / 选择其他图片",v->selectPage(3)));
        pageFrames[1].getViewTreeObserver().addOnGlobalLayoutListener(()->{
            if(destroyed||pageFrames[1].getVisibility()!=View.VISIBLE||pageFrames[1].getHeight()==0)return;
            int other=send.getPaddingTop()+send.getPaddingBottom()+AppUi.verticalExtent(previewCard)-preview.getHeight();
            for(int i=0;i<send.getChildCount();i++){View child=send.getChildAt(i);if(child!=previewCard&&child.getVisibility()!=View.GONE)other+=AppUi.verticalExtent(child);}
            int height=Math.max(dp(120),Math.min(dp(520),pageFrames[1].getHeight()-other-dp(8)));
            if(preview.getLayoutParams().height!=height){preview.getLayoutParams().height=height;preview.requestLayout();}
        });
        LinearLayout editor=pageFrames[3],editorHeader=new LinearLayout(this);editorHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView editorTitle=text("图片编辑",18,0xff142f2a);editorTitle.setTypeface(null,Typeface.BOLD);editorHeader.addView(editorTitle,new LinearLayout.LayoutParams(0,-2,1));
        editorHeader.addView(buttonToSend(),AppUi.params(this,-2,-2,0));editor.addView(editorHeader);
        editorLocked=text("NFC 操作运行中，图片编辑暂时锁定。发送进度可在发送页查看。",12,0xffa34b25);editorLocked.setVisibility(View.GONE);editor.addView(editorLocked);
        LinearLayout editorPreviewCard=surface();editor.addView(editorPreviewCard);
        editPreview=new PixelPreview(this);editPreview.setContentDescription("编辑后的实际发送像素预览，点击可放大");editPreview.setOnClickListener(v->showPixels());editorPreviewCard.addView(editPreview,new LinearLayout.LayoutParams(-1,dp(230)));
        editInfo=text("正在读取编辑草稿…",12,0xff52635d);editInfo.setMaxLines(2);editInfo.setOnClickListener(v->showImageSettings());editorPreviewCard.addView(editInfo);
        leaveCalibrationEdit=action("退出校色测试 · 恢复编辑",v->leaveCalibrationChart());leaveCalibrationEdit.setVisibility(View.GONE);editorPreviewCard.addView(leaveCalibrationEdit);
        LinearLayout tools=new LinearLayout(this);editor.addView(tools);
        for(int i=0;i<3;i++){final int index=i;editorTabs[i]=button(new String[]{"构图","色彩","更多"}[i]);editorTabs[i].setOnClickListener(v->selectEditor(index));tools.addView(editorTabs[i],weighted());}
        pageScrolls[3]=new ScrollView(this);editor.addView(pageScrolls[3],new LinearLayout.LayoutParams(-1,0,1));
        pageBodies[3]=column();pageScrolls[3].addView(pageBodies[3]);for(int i=0;i<3;i++){editorBodies[i]=surface();editorBodies[i].setPadding(dp(10),dp(6),dp(10),dp(16));pageBodies[3].addView(editorBodies[i]);}
        editor.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            if(destroyed||editor.getVisibility()!=View.VISIBLE||editor.getHeight()==0)return;
            int other=AppUi.verticalExtent(editorPreviewCard)-editPreview.getHeight();
            for(int i=0;i<editor.getChildCount();i++){View child=editor.getChildAt(i);if(child!=editorPreviewCard&&child!=pageScrolls[3]&&child.getVisibility()!=View.GONE)other+=AppUi.verticalExtent(child);}
            int height=Math.max(dp(96),Math.min(dp(280),Math.min((int)(editor.getHeight()*0.40),editor.getHeight()-other-dp(180))));
            if(editPreview.getLayoutParams().height!=height){editPreview.getLayoutParams().height=height;editPreview.requestLayout();}
        });
        page=editorBodies[0];page.addView(action("选择图片",v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,41);}));
        LinearLayout sources=new LinearLayout(this);sources.addView(action("最近图片",v->showRecentImages()),weighted());sources.addView(action("测试图",v->showTestPatterns()),weighted());page.addView(sources);
        fit=spinner(new String[]{"完整显示 · 留白","填满屏幕 · 裁剪"});page.addView(fit);
        LinearLayout cropActions=new LinearLayout(this);rotateImage=action("旋转 90°",v->{rotation=(rotation+90)%360;prepare();});cropImage=action("裁切",v->showCrop());restoreImage=action("还原原图",v->{if(originalSource!=null){source=originalSource;rotation=0;cropped=false;appliedCrop=null;prepare();}});
        cropActions.addView(rotateImage,weighted());cropActions.addView(cropImage,weighted());cropActions.addView(restoreImage,weighted());page.addView(cropActions);
        page.addView(text("预览来自实际发送像素；点击图片放大，点击图片名称查看参数。",12,0xff68766e));
        page=editorBodies[1];adjustments=new ImageAdjustmentUi(this,()->prepare());adjustments.addTo(page);
        page=editorBodies[2];binary=new CheckBox(this);binary.setText("黑白二值化");binary.setChecked(getSharedPreferences("refresh-settings",MODE_PRIVATE).getBoolean("binary",false));idleControls.add(binary);page.addView(binary);
        int savedThreshold=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("threshold",128);thresholdLabel=text("二值化阈值："+Math.max(0,Math.min(255,savedThreshold)),13,0xff52635d);page.addView(thresholdLabel);
        threshold=new SeekBar(this);threshold.setMax(255);threshold.setProgress(Math.max(0,Math.min(255,savedThreshold)));idleControls.add(threshold);page.addView(threshold);
        thresholdHint=text("阈值越高，黑色区域越多；松手更新预览。",12,0xff68766e);page.addView(thresholdHint);
        binary.setOnCheckedChangeListener((b,checked)->{getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putBoolean("binary",checked).apply();prepare();});
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean user){thresholdLabel.setText("二值化阈值："+value);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("threshold",s.getProgress()).apply();prepare();}});
        page.addView(text("图像简化",14,0xff52635d));blocks=spinner(new String[]{"原始细节","轻度 · 2×2 色块","中度 · 4×4 色块","强 · 8×8 色块","极强 · 16×16 色块"});page.addView(blocks);
        int savedBlock=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("blockSize",1),selectedBlock=0;for(int i=0;i<blockSizes.length;i++)if(blockSizes[i]==savedBlock)selectedBlock=i;blocks.setSelection(selectedBlock);
        dither=new CheckBox(this);dither.setText("抖动处理");dither.setChecked(selectedBlock==0&&getSharedPreferences("refresh-settings",MODE_PRIVATE).getBoolean("dither",false));idleControls.add(dither);page.addView(dither);
        ditherHint=text("",12,0xff68766e);page.addView(ditherHint);
        dither.setOnCheckedChangeListener((b,checked)->{getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putBoolean("dither",checked).apply();prepare();});
        blocks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("blockSize",blockSizes[pos]).apply();if(pos>0)dither.setChecked(false);prepare();}public void onNothingSelected(AdapterView<?> p){}});
        panelType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putString("panelType",selectedPanelType()).apply();prepare();controls();}public void onNothingSelected(AdapterView<?> p){}});
        fit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prepare();}public void onNothingSelected(AdapterView<?> p){}});

        page=pageBodies[2];section(page,"设置");page.addView(text("运行期间参数锁定，修改用于下一次操作。",12,0xff68766e));
        LinearLayout parameters=fold(page,"发送参数",false);
        section(parameters,"发送前充能时间");String[] chargeOptions=new String[31];for(int i=0;i<31;i++)chargeOptions[i]=i+" 秒"+(i==0?"（默认）":"");charge=spinner(chargeOptions);charge.setSelection(Math.max(0,Math.min(30,getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("chargeSeconds",0))));parameters.addView(charge);
        parameters.addView(text("连接屏幕后先保持贴合充能，再开始检查与传图。",12,0xff68766e));charge.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("chargeSeconds",pos).apply();}public void onNothingSelected(AdapterView<?> p){}});
        section(parameters,"发送后上电等待时间");power=spinner(new String[]{"500 ms","1000 ms","2000 ms（默认）","3000 ms","4000 ms","5000 ms"});int savedPower=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("powerMs",2000),selectedPower=2;for(int i=0;i<powerTimes.length;i++)if(powerTimes[i]==savedPower)selectedPower=i;power.setSelection(selectedPower);parameters.addView(power);
        power.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("powerMs",powerTimes[pos]).apply();}public void onNothingSelected(AdapterView<?> p){}});
        section(parameters,"状态查询间隔");poll=spinner(new String[]{"0 ms · 连续查询","10 ms","20 ms","50 ms","100 ms","200 ms","300 ms","400 ms","500 ms（默认）"});int savedPoll=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("pollMs",PanelProtocol.DEFAULT_POLL_MS),selectedPoll=pollTimes.length-1;for(int i=0;i<pollTimes.length;i++)if(pollTimes[i]==savedPoll)selectedPoll=i;poll.setSelection(selectedPoll);parameters.addView(poll);
        poll.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("pollMs",pollTimes[pos]).apply();}public void onNothingSelected(AdapterView<?> p){}});
        parameters.addView(text("0 ms 为收到应答后立即查询，实际频率取决于通信耗时。",12,0xff68766e));parameters.addView(action("恢复默认发送参数",v->{charge.setSelection(0);power.setSelection(2);poll.setSelection(pollTimes.length-1);}));
        LinearLayout compatibility=fold(page,"执行方式与兼容实验",false);
        compatibility.addView(text("执行方式 · 启动通道与调用身份",14,0xff52635d));
        launchMode=new BackendChoiceSpinner(this,"选择执行方式");idleControls.add(launchMode);
        new BackendChoiceAdapter(this,new String[]{"Shizuku 用户服务（默认）","Shizuku 进程兼容","Root 直连（旧版，无需 Shizuku）","前台 App 通信＋Root HAL（实验）"},new BackendChoiceAdapter.Details(){public String description(int position){return BackendOptions.launchDescription(position);}public String blockedReason(int position){return "";}}).bindTo(launchMode);
        launchMode.setSelection(Math.max(0,Math.min(3,getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("launchMode",0))));compatibility.addView(launchMode);
        launchExplanation=text("",13,0xff52635d);compatibility.addView(launchExplanation);
        launchMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(busy)return;getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("launchMode",pos).apply();resetConnection();}public void onNothingSelected(AdapterView<?> p){}});
        halManagerBody=column();halManagerBody.addView(text("Root 管理通道 · 仅用于 HAL 与会话锁",14,0xff52635d));
        halManager=new BackendChoiceSpinner(this,"选择 Root HAL 管理通道");idleControls.add(halManager);
        new BackendChoiceAdapter(this,new String[]{"Shizuku Root 用户服务","Shizuku Root 进程兼容","直接 Root 管理 HAL（无需 Shizuku）"},new BackendChoiceAdapter.Details(){
            public String description(int position){return position==0?"需要以 Root 启动 Shizuku；用户服务只管理 HAL，传图留在前台 App。":position==1?"绕开 UserService 启动异常；需要 Shizuku Root，传图留在前台 App。":"操作时请求 Root 授权；Root 只管理 HAL，不以 UID 0 寻卡或传图。";}
            public String blockedReason(int position){return "";}
        }).bindTo(halManager);
        halManager.setSelection(Math.max(0,Math.min(2,getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("halManager",0))));halManagerBody.addView(halManager);compatibility.addView(halManagerBody);
        halManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(busy)return;getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("halManager",pos).apply();if(selectedLaunch()==3)resetConnection();}public void onNothingSelected(AdapterView<?> p){}});
        compatibility.addView(text("Q-Poll 寻卡方式 · 控制器寻卡路径",14,0xff52635d));
        discoveryBackend=new BackendChoiceSpinner(this,"选择 Q-Poll 寻卡方式");idleControls.add(discoveryBackend);
        discoveryAdapter=new BackendChoiceAdapter(this,new String[]{"请选择寻卡方式","系统 QTag（默认）","NXP HAL 兼容（SN220 / SN100U，实验）","直接 Q-Poll（实验）"},new BackendChoiceAdapter.Details(){public String description(int position){return BackendOptions.discoveryDescription(position);}public String blockedReason(int position){return discoveryBlockedReason(position);}});
        discoveryAdapter.bindTo(discoveryBackend);discoveryBackend.setSelection(1);compatibility.addView(discoveryBackend);
        discoveryExplanation=text("",13,0xff52635d);compatibility.addView(discoveryExplanation);
        discoveryRestriction=text("",13,0xffa34b25);discoveryRestriction.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);compatibility.addView(discoveryRestriction);
        discoveryBackend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(initializing||busy||updatingBackendChoices)return;if(position>0&&discoveryBlockedReason(position).isEmpty())backendChoiceNotice="";controls();}public void onNothingSelected(AdapterView<?> parent){}});
        compatibility.addView(action("查看全部方式对比与使用条件",v->showBackendGuide()));
        compatibility.addView(text("可选仅表示满足已知前提，不代表已验证完整刷新。三种寻卡均依赖系统 ReaderMode 与标签回调；实验寻卡方式每次打开恢复系统 QTag，执行期间锁定，失败后不自动切换或重发。",12,0xff68766e));
        LinearLayout calibrationBody=fold(page,"屏幕校色",false);
        calibration=new CalibrationUi(this,new CalibrationUi.Host(){
            public void calibrationChanged(){if(!destroyed)prepare();}
            public void calibrationModal(boolean open){editingCrop=open;if(!destroyed)controls();}
            public void calibrationLoading(boolean loading){preparing=loading;if(!destroyed)controls();}
            public void showCalibrationChart(){enterCalibrationChart();}
        });calibration.addTo(calibrationBody);
        page.addView(action("重新查看使用引导",v->{getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putBoolean("setupComplete",false).apply();selectPage(0);controls();}));
        Button about=button("参考项目与许可证");about.setOnClickListener(v->showLicense());page.addView(about);page.addView(text("墨水屏刷新测试 · "+appVersion(),12,0xff68766e));
        log=text("",11,0xff233c31);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setPadding(dp(10),dp(8),dp(10),dp(8));
        selectPage(getSharedPreferences("refresh-settings",MODE_PRIVATE).getBoolean("setupComplete",false)?1:0);selectEditor(0);
        source=PanelImage.testPattern();originalSource=source;calibration.restore(saved);initializing=false;
        append(appVersion()+" 就绪；配置完成后进入刷图页。");restoreDraft();
        new Thread(()->{String config=ChipInfo.inspect();main.post(()->{if(destroyed)return;readableChipConfig=config;controls();});},"backend-chip-config").start();
        Shizuku.addBinderDeadListener(binderDead);Shizuku.addBinderReceivedListenerSticky(binderReceived);
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(nfcStateReceiver,new android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(nfcStateReceiver,new android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED));
    }
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout surface(){LinearLayout layout=column();layout.setPadding(dp(10),dp(6),dp(10),dp(6));layout.setBackground(round(Color.WHITE));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);layout.setLayoutParams(params);return layout;}
    private void primary(Button button){button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{Color.WHITE,0xff82918b}));button.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{0xff176b61,0xffe1e8e4}));}
    private LinearLayout fold(LinearLayout parent,String name,boolean open){
        Button toggle=button("");LinearLayout body=surface();body.setVisibility(open?View.VISIBLE:View.GONE);
        Runnable label=()->{boolean expanded=body.getVisibility()==View.VISIBLE;toggle.setText(name+(expanded?"  ▴":"  ▾"));toggle.setContentDescription(name+(expanded?"，已展开，点击收起":"，已收起，点击展开"));};
        toggle.setOnClickListener(v->{body.setVisibility(body.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);label.run();});label.run();parent.addView(toggle);parent.addView(body);return body;
    }
    private void selectEditor(int index){for(int i=0;i<3;i++){boolean selected=i==index;editorBodies[i].setVisibility(selected?View.VISIBLE:View.GONE);styleTab(editorTabs[i],selected);}pageScrolls[3].scrollTo(0,0);}
    private void styleTab(Button button,boolean selected){button.setSelected(selected);button.setTextColor(selected?Color.WHITE:0xff52635d);button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected?0xff176b61:0xffe3eae6));}
    private boolean nfcEnabled(){try{android.nfc.NfcAdapter adapter=android.nfc.NfcAdapter.getDefaultAdapter(this);return adapter!=null&&adapter.isEnabled();}catch(Exception e){return false;}}
    private boolean connectionReady(){
        if(controlLaunch()==2)return true;
        try{return Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==android.content.pm.PackageManager.PERMISSION_GRANTED&&(controlLaunch()!=0||(bridgeBinder!=null&&bridgeBinder.isBinderAlive()));}catch(Exception e){return false;}
    }
    private WorkflowStatus.Gate sendGate(){return WorkflowStatus.gate(nfcEnabled(),connectionReady(),!selectedPanelType().isEmpty(),prepared!=null&&prepared.panelType.equals(selectedPanelType())&&!preparing&&!restoringDraft);}
    private int optionLauncherUid(){
        if(controlLaunch()==2)return 0; // Requested identity; Root authorization is still deferred until execution.
        if(!connectionReady())return -1;
        try{return controlLaunch()==1?Shizuku.getUid():bridgeUid;}catch(Exception e){return -1;}
    }
    private String discoveryBlockedReason(int position){return BackendOptions.blockedReason(optionLauncherUid(),position,arm64Device,readableChipConfig);}
    private void updateBackendChoices(){
        if(initializing||updatingBackendChoices||discoveryAdapter==null)return;
        updatingBackendChoices=true;
        try{
            boolean front=selectedLaunch()==3;halManagerBody.setVisibility(front?View.VISIBLE:View.GONE);discoveryBackend.setVisibility(front?View.GONE:View.VISIBLE);
            if(front){
                launchExplanation.setText(BackendOptions.launchDescription(3)+"\n\n通信：前台 App UID="+android.os.Process.myUid()+"；Root 管理通道："+halManager.getSelectedItem());
                discoveryExplanation.setText("固定使用 NXP HAL 兼容（SN220 / SN100U），公开 ReaderMode 接收标签；普通 NFC-A 对照只持有会话锁，不注入 HAL。前台设置不改写原寻卡选择；切回后按原有条件重新检查。");
                String problem=discoveryBlockedReason(2);discoveryRestriction.setText(problem);discoveryRestriction.setVisibility(problem.isEmpty()?View.GONE:View.VISIBLE);return;
            }
            int uid=optionLauncherUid(),position=discoveryBackend.getSelectedItemPosition();
            String signature=selectedLaunch()+":"+uid+":"+arm64Device+":"+readableChipConfig;
            if(!signature.equals(backendChoiceSignature)){backendChoiceSignature=signature;discoveryAdapter.notifyDataSetChanged();}
            String reason=discoveryBlockedReason(position);
            if(position>0&&BackendOptions.retainedSelection(uid,position,arm64Device,readableChipConfig,busy)!=position){
                backendChoiceNotice="原寻卡选择已清空："+reason+"。请手动重新选择；不会自动切换后端。";discoveryBackend.setSelection(0);position=0;
            }
            String identity=controlLaunch()==2?"计划使用 UID 0；操作开始时请求 Root 授权，当前尚未验证 Root 授权或 ROM 是否接受该调用。":uid==0?"已连接 Shizuku Root（启动 UID 0）；NFC worker 使用 UID 2000 / shell。":uid==2000?"已连接 Shizuku Shell（UID 2000）；NFC worker 使用 UID 2000 / shell。":"Shizuku 连接或身份尚未确认；只有权限授予不代表用户服务已连接。";
            launchExplanation.setText(BackendOptions.launchDescription(selectedLaunch())+"\n\n"+identity);
            String selected=BackendOptions.discoveryDescription(position);
            if(position==2&&discoveryBlockedReason(position).isEmpty()){selected+="\n可选仅表示 Root / 架构 / 可读配置未发现冲突；实际 HAL 与完整刷新仍需验证。";if(readableChipConfig==null||!readableChipConfig.startsWith("NXP "))selected+=" 当前未能从普通 App 权限读取芯片配置，执行时仍会再次检查。";}
            discoveryExplanation.setText(selected);
            String unavailable=discoveryBlockedReason(2),notice=backendChoiceNotice;
            if(!unavailable.isEmpty())notice+=(notice.isEmpty()?"":"\n\n")+"NXP HAL 当前不可选："+unavailable;
            discoveryRestriction.setText(notice);discoveryRestriction.setVisibility(notice.isEmpty()?View.GONE:View.VISIBLE);
        }finally{updatingBackendChoices=false;}
    }
    private void showBackendGuide(){
        StringBuilder guide=new StringBuilder("执行方式决定启动通道与调用身份；寻卡方式决定如何进入 Q-Poll。原三种执行方式与寻卡方式可组合；前台 App 路径固定使用 HAL，所有组合均需分别实测。\n\n");
        String[] launches={"Shizuku 用户服务","Shizuku 进程兼容","Root 直连","前台 App 通信＋Root HAL"};for(int i=0;i<launches.length;i++)guide.append(launches[i]).append("\n").append(BackendOptions.launchDescription(i)).append("\n\n");
        String[] discoveries={"系统 QTag","NXP HAL 兼容（SN220 / SN100U）","直接 Q-Poll"};for(int i=0;i<discoveries.length;i++)guide.append(discoveries[i]).append("\n").append(BackendOptions.discoveryDescription(i+1)).append("\n\n");
        guide.append("组合条件\nShizuku Shell 无法准备 NXP HAL 注入，选项禁用；Shizuku Root 或 Root 直连可尝试准备，但还需 arm64 与匹配的 SN220 / SN100U HAL。Root 直连仍调用系统 NFC 服务，ROM 若拒绝 UID 0 的 ReaderMode，三种寻卡方式都会受到影响。\n\n验证边界\n控制器应答、实际标签回调、ISO-DEP、屏幕规格检查、传图、刷新状态与实物画面需要分别确认。可选、Root 权限或 API 返回成功均不代表完整刷屏已验证。改变选择不会自动检测、注入、寻卡或发送。");
        TextView body=text(guide.toString(),14,0xff263e35);body.setPadding(dp(16),dp(8),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(body);new AlertDialog.Builder(this).setTitle("执行方式与寻卡方式").setView(scroll).setPositiveButton("关闭",null).show();
    }
    private String backendProblem(){
        int position=selectedLaunch()==3?2:discoveryBackend.getSelectedItemPosition();
        String reason=discoveryBlockedReason(position);if(!reason.isEmpty())return reason;
        if(!connectionReady())return "";
        try{BackendPolicy.validate(optionLauncherUid(),"refresh",BackendOptions.transport(position));return "";}catch(Exception e){return e.getMessage()==null?"执行方式与寻卡配置不匹配":e.getMessage();}
    }
    private void updateReadiness(){
        if(initializing||readiness==null)return;
        boolean nfc=nfcEnabled(),connected=connectionReady(),panel=!selectedPanelType().isEmpty();WorkflowStatus.Gate gate=sendGate();String problem=backendProblem();
        setupInfo.setText("① NFC："+(nfc?"已开启":"请开启")+"\n② 连接："+(controlLaunch()==2?"Root 将在执行时请求授权":connected?"已连接":"待授权 / 连接")+"\n③ 屏幕类型："+(panel?selectedPanelType():"请选择")+"\n设置完成后选图、调整并发送；刷新兼容性以实测为准。");
        setupDone.setEnabled(!busy&&!preparing&&!restoringDraft&&nfc&&connected&&panel&&problem.isEmpty());
        String message=gate==WorkflowStatus.Gate.NFC?"NFC 未开启，请先开启系统 NFC。":gate==WorkflowStatus.Gate.CONNECTION?"需要连接 Shizuku；连接后请重新点击发送。":gate==WorkflowStatus.Gate.PANEL?"请在设备页选择屏幕类型。":gate==WorkflowStatus.Gate.IMAGE?"正在准备预览，请稍候。":!problem.isEmpty()?"执行方式与寻卡配置不匹配，请在设置页调整。":"屏幕 "+selectedPanelType()+" · "+(controlLaunch()==2?"Root 在开始时请求授权":"授权已连接")+" · 点击发送后核对并确认";
        readiness.setText(message);boolean action=!busy&&!preparing&&!restoringDraft&&(gate!=WorkflowStatus.Gate.READY||!problem.isEmpty())&&gate!=WorkflowStatus.Gate.IMAGE;
        readinessAction.setVisibility(action?View.VISIBLE:View.GONE);readinessAction.setText(gate==WorkflowStatus.Gate.NFC?"打开 NFC 设置":gate==WorkflowStatus.Gate.CONNECTION?"前往设备页连接":!problem.isEmpty()?"调整执行方式":"选择屏幕类型");
        refresh.setEnabled(!busy&&!preparing&&!editingCrop&&!restoringDraft&&gate==WorkflowStatus.Gate.READY&&problem.isEmpty());
        if(!busy&&!terminal){state.setText(gate==WorkflowStatus.Gate.READY&&problem.isEmpty()?"可以准备发送":"需要完成设备设置");state.setTextColor(0xff176b61);detail.setText(message);}
    }
    private void resolveReadiness(){
        terminal=false;resultAction.setVisibility(View.GONE);failureInfo.setVisibility(View.GONE);lastAdvice="";
        if(!nfcEnabled()){try{startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));}catch(Exception e){Toast.makeText(this,"请在系统设置中开启 NFC",Toast.LENGTH_LONG).show();}}
        else selectPage(!backendProblem().isEmpty()?2:0);controls();
    }
    private boolean checkBeforeSend(){
        controls();if(sendGate()!=WorkflowStatus.Gate.READY||!backendProblem().isEmpty()){resolveReadiness();return false;}return true;
    }
    private void setImageStatus(String message){imageInfo.setText(message);editInfo.setText(message);}
    private void showImageSettings(){if(prepared!=null)new AlertDialog.Builder(this).setTitle(imageName).setMessage(prepared.settings+"\n\n552×768 · "+prepared.packed.length+" 字节").setPositiveButton("关闭",null).show();}
    private void showAdvice(){
        new AlertDialog.Builder(this).setTitle(state.getText()).setMessage(lastAdvice.isEmpty()?detail.getText():lastAdvice).setNegativeButton("关闭结果",(d,w)->{if(!busy){terminal=false;resultAction.setVisibility(View.GONE);failureInfo.setVisibility(View.GONE);sessionSteps.setVisibility(View.GONE);controls();}}).setNeutralButton("设备设置",(d,w)->selectPage(0)).setPositiveButton("查看日志",(d,w)->showLogs()).show();
    }
    private void syncDeviceFeedback(){
        if(deviceFeedback==null)return;
        deviceFeedback.setVisibility(busy||terminal?View.VISIBLE:View.GONE);
        if(lastSessionMode.equals("refresh"))deviceFeedback.setText("发送任务的进度与结果显示在发送页，可随时切换查看。");
        else deviceFeedback.setText(state.getText()+"\n"+detail.getText()+(failureInfo.getVisibility()==View.VISIBLE?"\n"+failureInfo.getText():""));
    }
    private void showFailure(String cause){
        WorkflowStatus.Advice advice=WorkflowStatus.failure(activeMode,sessionStage,cause);state.setText(advice.title);state.setTextColor(0xffa34b25);detail.setText(advice.message);failureInfo.setText("原始错误信息 / 错误码\n"+(cause==null||cause.isEmpty()?"未返回具体错误信息":cause));failureInfo.setVisibility(View.VISIBLE);lastAdvice=advice.message+"\n\n发生阶段："+sessionStage+"\n\n技术信息：\n"+cause;resultAction.setVisibility(View.VISIBLE);progress.setVisibility(View.GONE);chargeTiming.setVisibility(View.GONE);refreshTiming.setVisibility(View.GONE);syncDeviceFeedback();
    }
    private static final class EditorSnapshot {
        Bitmap original,current;CropWindow crop;int rotation,cropRotation,fit;boolean cropped;String name;TestImageMode mode;
    }
    private EditorSnapshot editorSnapshot(){
        EditorSnapshot snapshot=new EditorSnapshot();snapshot.original=originalSource;snapshot.current=source;snapshot.crop=appliedCrop==null?null:new CropWindow(appliedCrop);snapshot.rotation=rotation;snapshot.cropRotation=cropRotation;snapshot.fit=fit.getSelectedItemPosition();snapshot.cropped=cropped;snapshot.name=imageName;snapshot.mode=imageMode;return snapshot;
    }
    private void applyEditor(EditorSnapshot snapshot){
        originalSource=snapshot.original;source=snapshot.current;appliedCrop=snapshot.crop;rotation=snapshot.rotation;cropRotation=snapshot.cropRotation;cropped=snapshot.cropped;imageName=snapshot.name;imageMode=snapshot.mode;fit.setSelection(snapshot.fit);
    }
    private void enterCalibrationChart(){
        if(busy||preparing||editingCrop)return;
        if(imageMode!=TestImageMode.CALIBRATION)calibrationReturn=editorSnapshot();saveDraft();
        source=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);int[] pixels=new int[552*768];byte[] codes=CalibrationPalette.chart(552,768);for(int i=0;i<pixels.length;i++)pixels[i]=ImageRaster.color(codes[i]);source.setPixels(pixels,0,552,0,0,552,768);
        originalSource=source;cropped=false;appliedCrop=null;rotation=0;imageMode=TestImageMode.CALIBRATION;imageName="校色基准图";selectPage(1);prepare();
    }
    private void leaveCalibrationChart(){if(busy||preparing||editingCrop||calibrationReturn==null)return;EditorSnapshot snapshot=calibrationReturn;calibrationReturn=null;applyEditor(snapshot);prepare();}
    private byte[] png(Bitmap bitmap)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("无法保存草稿图片");return bytes.toByteArray();}
    private void scheduleDraft(){if(initializing||restoringDraft||destroyed)return;main.removeCallbacks(saveDraftLater);main.postDelayed(saveDraftLater,350);}
    private void saveDraft(){
        if(initializing||restoringDraft||source==null||draftWorker.isShutdown())return;
        final EditorSnapshot snapshot=imageMode==TestImageMode.CALIBRATION?calibrationReturn:editorSnapshot();if(snapshot==null)return;
        final int token=++draftRevision;
        try{
            JSONObject json=new JSONObject();json.put("mode",snapshot.mode.name());json.put("name",snapshot.name);json.put("rotation",snapshot.rotation);json.put("cropRotation",snapshot.cropRotation);json.put("fit",snapshot.fit);json.put("cropped",snapshot.cropped);
            if(snapshot.cropped&&snapshot.crop!=null){json.put("left",snapshot.crop.left());json.put("top",snapshot.crop.top());json.put("width",snapshot.crop.width());}
            final String settings=json.toString();
            draftWorker.execute(()->{try{
                if(token!=draftRevision)return;
                byte[] original=snapshot.original==savedDraftOriginal?savedOriginalPng:png(snapshot.original);
                byte[] current=snapshot.current==snapshot.original?original:snapshot.current==savedDraftCurrent?savedCurrentPng:png(snapshot.current);
                if(token!=draftRevision)return;draftStore.save(new ImageDraftStore.Draft(settings,original,current));savedDraftOriginal=snapshot.original;savedOriginalPng=original;savedDraftCurrent=snapshot.current;savedCurrentPng=current;
            }catch(Exception|OutOfMemoryError e){main.post(()->{if(!destroyed)append("编辑草稿未保存："+e.getMessage());});}});
        }catch(Exception e){append("编辑草稿参数无效："+e.getMessage());}
    }
    private Bitmap decodeDraft(byte[] png)throws IOException {
        android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(png,0,png.length,options);
        if(options.outWidth<1||options.outHeight<1||Math.max(options.outWidth,options.outHeight)>2048)throw new IOException("草稿图片尺寸无效");
        Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(png,0,png.length);if(bitmap==null)throw new IOException("无法解码草稿图片");return bitmap;
    }
    private void restoreDraft(){
        restoringDraft=true;controls();
        draftWorker.execute(()->{EditorSnapshot snapshot=null;String warning="";
            try{ImageDraftStore.Draft draft=draftStore.load();if(draft!=null){
                JSONObject json=new JSONObject(draft.settings);snapshot=new EditorSnapshot();snapshot.original=decodeDraft(draft.original);snapshot.current=draft.current==draft.original?snapshot.original:decodeDraft(draft.current);snapshot.mode=TestImageMode.valueOf(json.getString("mode"));snapshot.name=json.getString("name");snapshot.rotation=json.getInt("rotation");snapshot.cropRotation=json.getInt("cropRotation");snapshot.fit=json.getInt("fit");snapshot.cropped=json.getBoolean("cropped");
                if(snapshot.mode==TestImageMode.CALIBRATION||snapshot.rotation<0||snapshot.rotation>270||snapshot.rotation%90!=0||snapshot.cropRotation<0||snapshot.cropRotation>270||snapshot.cropRotation%90!=0||snapshot.fit<0||snapshot.fit>1)throw new IOException("草稿参数无效");
                if(snapshot.cropped){boolean swap=snapshot.cropRotation%180!=0;int w=swap?snapshot.original.getHeight():snapshot.original.getWidth(),h=swap?snapshot.original.getWidth():snapshot.original.getHeight();snapshot.crop=CropWindow.restore(w,h,json.getDouble("left"),json.getDouble("top"),json.getDouble("width"));}
            }}catch(Exception|OutOfMemoryError e){snapshot=null;warning="无法恢复上次编辑，已打开测试图："+e.getMessage();}
            final EditorSnapshot restored=snapshot;final String notice=warning;
            main.post(()->{if(destroyed)return;if(restored!=null)applyEditor(restored);restoringDraft=false;if(!notice.isEmpty())append(notice);prepare();});
        });
    }
    private Button buttonToSend(){Button button=button("完成编辑 · 去发送");button.setOnClickListener(v->selectPage(1));return button;}
    private String appVersion(){
        try{
            String name=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
            return name==null||name.isEmpty()?"版本未知":"v"+name;
        }catch(android.content.pm.PackageManager.NameNotFoundException e){return "版本未知";}
    }
    private void showTestPatterns(){
        if(busy||preparing||editingCrop)return;
        final String[] names={"四色测试图","色阶与抖动测试图","纯黑测试图","纯白测试图","纯黄测试图","纯红测试图"};
        final TestImageMode[] modes={TestImageMode.FOUR_COLOR,TestImageMode.TONES,TestImageMode.BLACK,TestImageMode.WHITE,TestImageMode.YELLOW,TestImageMode.RED};
        new AlertDialog.Builder(this).setTitle("选择测试图 · 不使用校色数据").setItems(names,(dialog,index)->{
            if(busy||preparing||editingCrop)return;
            calibrationReturn=null;imageMode=modes[index];source=index==0?PanelImage.testPattern():index==1?PanelImage.tonePattern():PanelImage.solidPattern(index-2);originalSource=source;cropped=false;rotation=0;imageName=names[index];prepare();
        }).setNegativeButton("取消",null).show();
    }
    private Bitmap recentThumbnail(RecentImageStore.Entry entry)throws IOException {
        String path=recentImages.file(entry.id).getAbsolutePath();android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(path,options);
        if(options.outWidth<1||options.outHeight<1)return null;
        int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>512)sample*=2;options.inJustDecodeBounds=false;options.inSampleSize=sample;
        Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path,options);if(bitmap==null)return null;
        int longest=Math.max(bitmap.getWidth(),bitmap.getHeight());if(longest<=384)return bitmap;
        float scale=384f/longest;Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);if(small!=bitmap)bitmap.recycle();return small;
    }
    private void fillRecentGrid(LinearLayout grid,List<RecentImageStore.Entry> entries,Map<String,Bitmap> thumbnails,AlertDialog dialog){
        grid.removeAllViews();
        for(int i=0;i<entries.size();i+=2){
            LinearLayout row=new LinearLayout(this);grid.addView(row);
            for(int column=0;column<2;column++){
                if(i+column>=entries.size()){row.addView(new View(this),AppUi.params(this,0,1,1));continue;}
                final RecentImageStore.Entry entry=entries.get(i+column);String date=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(entry.usedAt));
                LinearLayout card=surface();card.setPadding(dp(6),dp(6),dp(6),dp(6));row.addView(card,AppUi.params(this,0,-2,1));
                View.OnClickListener select=v->{editingCrop=false;dialog.dismiss();loadRecentImage(entry);};
                ImageView thumbnail=new ImageView(this);thumbnail.setImageBitmap(thumbnails.get(entry.id));thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);thumbnail.setBackgroundColor(0xffe3e9e5);thumbnail.setContentDescription("选用最近图片，"+date);thumbnail.setFocusable(true);thumbnail.setOnClickListener(select);card.addView(thumbnail,new LinearLayout.LayoutParams(-1,dp(176)));
                TextView used=text(date,12,0xff52635d);used.setGravity(android.view.Gravity.CENTER);card.addView(used);
                LinearLayout actions=new LinearLayout(this);Button choose=button("选用"),remove=button("删除");choose.setTextSize(13);remove.setTextSize(13);choose.setOnClickListener(select);
                remove.setContentDescription("删除 "+date+" 的最近图片副本");remove.setOnClickListener(v->{try{recentImages.remove(entry.id);entries.remove(entry);thumbnails.remove(entry.id);if(entries.isEmpty())dialog.dismiss();else fillRecentGrid(grid,entries,thumbnails,dialog);}catch(IOException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});
                actions.addView(choose,weighted());actions.addView(remove,weighted());card.addView(actions);
            }
        }
    }
    private void showRecentImages(){
        if(busy||preparing||editingCrop)return;
        editingCrop=true;controls();
        new Thread(()->{try{
            final List<RecentImageStore.Entry> entries=recentImages.list();final Map<String,Bitmap> thumbnails=new HashMap<>();
            for(RecentImageStore.Entry entry:entries)thumbnails.put(entry.id,recentThumbnail(entry));
            main.post(()->{
                if(destroyed)return;
                if(entries.isEmpty()){editingCrop=false;controls();Toast.makeText(this,"暂无最近图片，选择图片后会自动保存在本机",Toast.LENGTH_LONG).show();return;}
                LinearLayout list=column();list.setPadding(dp(8),0,dp(8),dp(8));list.addView(text("最多 20 张本机副本；点击图片即可选用。\n重新应用当前参数；删除不会影响相册原图。",12,0xff52635d));
                LinearLayout grid=column();list.addView(grid);ScrollView scroll=new ScrollView(this);scroll.addView(list);
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("最近使用的图片").setView(scroll).setNegativeButton("关闭",null).setNeutralButton("清空记录",null).create();recentDialog=dialog;
                fillRecentGrid(grid,entries,thumbnails,dialog);
                dialog.setOnDismissListener(d->{recentDialog=null;editingCrop=false;if(!destroyed)controls();});dialog.show();
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空最近图片？").setMessage("仅删除本 App 保存的图片副本，不影响相册原图和当前编辑图片。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{try{recentImages.clear();dialog.dismiss();}catch(IOException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}).show());
            });
        }catch(Exception|OutOfMemoryError e){main.post(()->{if(destroyed)return;editingCrop=false;controls();Toast.makeText(this,"读取最近图片失败："+e.getMessage(),Toast.LENGTH_LONG).show();});}},"recent-images").start();
    }
    private void loadRecentImage(RecentImageStore.Entry entry){
        if(busy||preparing||editingCrop||destroyed)return;
        preparing=true;controls();setImageStatus("正在读取最近图片…");
        new Thread(()->{try{
            Bitmap image=PanelImage.load(getContentResolver(),android.net.Uri.fromFile(recentImages.file(entry.id)));recentImages.touch(entry.id);
            main.post(()->{if(!destroyed)useSelectedImage(image,"最近使用的图片");});
        }catch(Exception e){main.post(()->{if(destroyed)return;preparing=false;controls();setImageStatus("最近图片读取失败："+e.getMessage());});}},"recent-image-load").start();
    }
    private void useSelectedImage(Bitmap image,String name){
        calibrationReturn=null;imageMode=TestImageMode.NONE;source=image;originalSource=image;cropped=false;appliedCrop=null;rotation=0;imageName=name;preparing=false;prepare();
    }
    private void section(LinearLayout page,String title){TextView t=text(title,18,0xff142f2a);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(6));page.addView(t);}
    private void selectPage(int index){activePage=Math.max(0,Math.min(3,index));for(int i=0;i<4;i++){pageFrames[i].setVisibility(i==activePage?View.VISIBLE:View.GONE);styleTab(tabs[i],i==activePage);}}
    private void showLogs(){if(log.getParent()!=null)((android.view.ViewGroup)log.getParent()).removeView(log);ScrollView scroll=new ScrollView(this);scroll.addView(log);new AlertDialog.Builder(this).setTitle("运行日志").setView(scroll).setNeutralButton("复制",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("墨水屏日志",transcript.toString()));Toast.makeText(this,"日志已复制",Toast.LENGTH_SHORT).show();}).setPositiveButton("关闭",null).show();}
    private LinearLayout.LayoutParams weighted(){return AppUi.params(this,0,-2,1);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    private GradientDrawable round(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(12));return g;}
    private Button button(String s){return AppUi.button(this,s);}
    private Button action(String s,View.OnClickListener listener){Button b=button(s);b.setOnClickListener(listener);idleControls.add(b);return b;}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values));idleControls.add(s);return s;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private int selectedLaunch(){return launchMode==null?0:launchMode.getSelectedItemPosition();}
    private int controlLaunch(){return selectedLaunch()==3?(halManager==null?0:halManager.getSelectedItemPosition()):selectedLaunch();}
    private boolean currentBridgeName(android.content.ComponentName name){return name!=null&&name.getClassName().equals(selectedLaunch()==3?RootHalBridge.class.getName():ShellBridge.class.getName());}
    private void resetConnection(){
        main.removeCallbacks(bridgeTimeout);bridgeBinding=false;bridgeBinder=null;bridgeUid=-1;
        for(Shizuku.UserServiceArgs args:new Shizuku.UserServiceArgs[]{bridgeArgs,halBridgeArgs})try{Shizuku.unbindUserService(args,bridgeConnection,false);}catch(Exception ignored){}
        connectShizuku(false);
    }
    private void unbindHalBridge(){try{Shizuku.unbindUserService(halBridgeArgs,bridgeConnection,false);}catch(Exception ignored){}}
    private String selectedPanelType(){int pos=panelType.getSelectedItemPosition();return pos==1?"A0":pos==2?"A1":pos==3?"A0X":"";}
    private void controls(){
        if(initializing)return;
        updateBackendChoices();
        boolean enabled=!busy&&!preparing&&!editingCrop&&!restoringDraft;for(View view:idleControls)view.setEnabled(enabled);
        boolean powering=busy&&activeMode.equals("power");powerSupply.setEnabled(!stopping&&(enabled||powering));powerSupply.setText(stopping&&powering?"正在关闭供能…":powering?"关闭 NFC 供能":"开启 NFC 供能");
        boolean edit=enabled&&!imageMode.fixedRaster();for(View view:new View[]{rotateImage,cropImage,restoreImage,fit,binary,blocks})view.setEnabled(edit);
        boolean mono=binary.isChecked();thresholdLabel.setVisibility(mono?View.VISIBLE:View.GONE);threshold.setVisibility(mono?View.VISIBLE:View.GONE);thresholdHint.setVisibility(mono?View.VISIBLE:View.GONE);threshold.setEnabled(edit&&mono);
        boolean simplified=blocks.getSelectedItemPosition()>0;dither.setEnabled(edit&&!simplified);ditherHint.setText(imageMode.fixedRaster()?"当前为固定编码测试图，图像调整已锁定。":simplified?"已启用色块简化，抖动自动关闭。档位越强细节越少；供能或稳定性变化需实测。":"抖动使用色点模拟色阶。供能和刷新稳定性请以实测为准。");
        adjustments.setEditable(edit);stop.setEnabled(busy&&!stopping);stop.setVisibility(busy?View.VISIBLE:View.GONE);calibration.setEditable(enabled);deviceStop.setEnabled(busy&&!stopping);deviceStop.setVisibility(busy?View.VISIBLE:View.GONE);editorLocked.setVisibility(busy?View.VISIBLE:View.GONE);
        leaveCalibration.setVisibility(imageMode==TestImageMode.CALIBRATION&&calibrationReturn!=null?View.VISIBLE:View.GONE);leaveCalibrationEdit.setVisibility(leaveCalibration.getVisibility());updateReadiness();syncDeviceFeedback();
    }
    private void prepare(){
        if(initializing||restoringDraft||source==null||busy||destroyed||editingCrop)return;
        final int token=++revision,angle=rotation,block=blockSizes[blocks.getSelectedItemPosition()],level=threshold.getProgress();final boolean cover=fit.getSelectedItemPosition()==1,noise=dither.isChecked(),mono=binary.isChecked(),hasCrop=cropped;final Bitmap input=source;final String chosenPanel=selectedPanelType();
        final int[] palette=calibration==null?null:calibration.activePalette();final TestImageMode mode=imageMode;
        final ImageAdjustments tones=adjustments==null?ImageAdjustments.NONE:adjustments.snapshot();
        prepared=null;preparing=true;controls();setImageStatus("正在生成四色预览…");
        new Thread(()->{try{PanelImage.Prepared image=PanelImage.prepare(input,angle,cover,noise,block,mono,level,hasCrop,chosenPanel,palette,mode,tones);
            main.post(()->{if(token!=revision||destroyed)return;prepared=image;preview.setImage(image.preview);editPreview.setImage(image.preview);setImageStatus(imageName+" · "+(imageMode==TestImageMode.NONE?"点击查看参数":"测试图不使用校色 · 点击查看参数"));preparing=false;controls();scheduleDraft();});
        }catch(Throwable e){main.post(()->{if(token!=revision||destroyed)return;preparing=false;controls();setImageStatus("预览失败："+e.getMessage());});}},"image-preview").start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(calibration!=null&&calibration.onActivityResult(request,result,data))return;
        if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();preparing=true;controls();setImageStatus("正在读取图片…");
        new Thread(()->{try{Bitmap image=PanelImage.load(getContentResolver(),uri);
            String warning="";try{ByteArrayOutputStream png=new ByteArrayOutputStream();if(!image.compress(Bitmap.CompressFormat.PNG,100,png))throw new IOException("无法生成图片副本");recentImages.add(png.toByteArray());}catch(Exception e){warning="图片已打开，但未保存到最近图片："+e.getMessage();}
            final String notice=warning;main.post(()->{if(destroyed)return;useSelectedImage(image,"自选图片");if(!notice.isEmpty())Toast.makeText(this,notice,Toast.LENGTH_LONG).show();});}
        catch(Throwable e){main.post(()->{if(destroyed)return;preparing=false;controls();setImageStatus("图片读取失败："+e.getMessage());});}},"image-load").start();
    }
    private void showPixels(){
        if(prepared==null||busy||preparing||editingCrop)return;
        final Bitmap snapshot=prepared.preview;android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Material_Light_NoActionBar);previewDialog=dialog;dialog.setOnDismissListener(d->previewDialog=null);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setBackgroundColor(0xfff4f7f5);content.setPadding(dp(12),dp(12),dp(12),dp(12));
        content.addView(text("最终发送像素 · 552 × 768",20,0xff142f2a));content.addView(text("双指缩放 · 单指拖动 · 双击切换缩放；"+(imageMode==TestImageMode.NONE?"校色开启时以近似实物色显示":"测试图不使用校色数据"),12,0xff52635d));
        ZoomPreview viewer=new ZoomPreview(this,snapshot);content.addView(viewer,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);Button fitButton=button("适应屏幕"),actual=button("1:1 像素"),close=button("关闭");fitButton.setOnClickListener(v->viewer.fit());actual.setOnClickListener(v->viewer.actual());close.setOnClickListener(v->dialog.dismiss());actions.addView(fitButton,weighted());actions.addView(actual,weighted());actions.addView(close,weighted());content.addView(actions);
        dialog.setContentView(content);dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    private void showCrop(){
        if(source==null||busy||preparing||editingCrop)return;
        try {
            final int angle=((cropped?cropRotation:0)+rotation)%360;
            // Always edit the full source; the last applied window is copied so cancel is harmless.
            final CropView cropView=new CropView(this,PanelImage.rotated(originalSource,angle),cropped&&angle==cropRotation?appliedCrop:null);
            LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),0,dp(12),0);
            content.addView(text("单指拖动，双指缩放；白框内为保留区域。",13,0xff52635d));
            content.addView(cropView,new LinearLayout.LayoutParams(-1,Math.min(dp(380),getResources().getDisplayMetrics().heightPixels/2)));
            editingCrop=true;controls();
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("裁切图片 · 屏幕比例").setView(content).setNegativeButton("取消",null).setNeutralButton("重置裁切",null).setPositiveButton("应用裁切",null).create();
            dialog.setOnDismissListener(d->{editingCrop=false;if(!destroyed)controls();});dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->cropView.reset());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                Bitmap result=cropView.result();appliedCrop=cropView.snapshot();cropRotation=angle;source=result;rotation=0;cropped=true;
                // Dismiss callbacks can arrive later. Release this guard before prepare(),
                // even when "fill" was already selected and no spinner callback will fire.
                editingCrop=false;
                dialog.dismiss();fit.setSelection(1);prepare();
            }catch(Exception e){Toast.makeText(this,"裁切失败："+e.getMessage(),Toast.LENGTH_LONG).show();}});
        }catch(Exception e){editingCrop=false;controls();Toast.makeText(this,"无法打开裁切："+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void confirmRefresh(){
        if(busy||preparing||editingCrop||prepared==null||!checkBeforeSend())return;
        if(prepared.panelType.isEmpty()){Toast.makeText(this,"请先选择屏幕类型 A0、A1 或 A0X",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("将当前预览写入墨水屏？")
            .setMessage("这会替换当前贴合墨水屏的画面。\n\n目标：相同原厂配置的 552×768 四色屏（不限 UID）\n图片："+imageName+"\n"+prepared.settings+"\n发送前充能："+charge.getSelectedItemPosition()+" 秒\n上电等待："+powerTimes[power.getSelectedItemPosition()]+" ms\n忙状态查询间隔："+pollTimes[poll.getSelectedItemPosition()]+" ms\n\n仅贴放要刷新的那块屏。请贴稳，全程保持应用在前台和 NFC 供能，直到页面提示本轮结束。中断可能留下不完整画面。")
            .setNegativeButton("返回预览",null).setPositiveButton("确认并开始刷新",(d,w)->start("refresh")).show();
    }
    private void start(final String mode){
        if(busy||preparing||editingCrop||restoringDraft||!resumed)return;
        if(mode.equals("refresh")&&!checkBeforeSend())return;
        if(!nfcEnabled()){terminal=false;updateReadiness();resolveReadiness();return;}
        lastSessionMode=mode;
        final int launch=selectedLaunch();
        final int manager=controlLaunch();
        if(manager!=2)try{if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED||(manager==0&&(bridgeBinder==null||!bridgeBinder.isBinderAlive()))){selectPage(0);connectShizuku();detail.setText("本轮尚未启动。连接完成后，请重新点击操作。");return;}}catch(Exception e){append("请先连接 Shizuku："+e);selectPage(0);controls();return;}
        if(BackendPolicy.needsQPoll(mode)){String reason=discoveryBlockedReason(launch==3?2:discoveryBackend.getSelectedItemPosition());if(!reason.isEmpty()){append("本轮未启动："+reason);selectPage(2);controls();return;}}
        final String transport=BackendPolicy.needsQPoll(mode)?launch==3?"sn220":BackendOptions.transport(discoveryBackend.getSelectedItemPosition()):"system";
        try{BackendPolicy.validate(manager==2?0:manager==1?Shizuku.getUid():bridgeUid,mode,transport);if(launch==3&&!mode.equals("inspect")&&optionLauncherUid()!=0)throw new SecurityException("前台 HAL 管理需要 Root");}catch(Exception e){append(e.getMessage());terminal=true;showFailure(e.getMessage());return;}
        final int seconds=mode.equals("power")?BackendPolicy.POWER_SECONDS:durations[duration.getSelectedItemPosition()];
        if(mode.equals("refresh")&&(prepared==null||prepared.panelType.isEmpty())){append("请先在设备页选择屏幕类型 A0、A1 或 A0X。");return;}
        final String id=Long.toString(SystemClock.elapsedRealtime());
        final File sessionStop=new File(getFilesDir(),"stop-"+id),jobFile=new File(getFilesDir(),"job-"+id+".json"),imageFile=new File(getFilesDir(),"image-"+id+".bin");
        try{
            {JSONObject job=new JSONObject();job.put("discoveryBackend",transport);
                if(mode.equals("refresh")){byte[] image=prepared.packed.clone();try(FileOutputStream out=new FileOutputStream(imageFile)){out.write(image);}String sha=PanelProtocol.toHex(MessageDigest.getInstance("SHA-256").digest(image));
                    job.put("panelType",prepared.panelType);job.put("imageFile",imageFile.getAbsolutePath());job.put("sha256",sha);job.put("chargeMs",charge.getSelectedItemPosition()*1000);job.put("powerMs",powerTimes[power.getSelectedItemPosition()]);job.put("pollMs",pollTimes[poll.getSelectedItemPosition()]);append("图片参数："+prepared.settings);append("发送前充能="+charge.getSelectedItemPosition()+" 秒");append("预览快照 SHA-256="+sha);}
                try(FileOutputStream out=new FileOutputStream(jobFile)){out.write(job.toString().getBytes("UTF-8"));}}
        }catch(Exception e){append("准备失败："+e);terminal=true;sessionStage="connecting";showFailure("准备图片与任务失败："+e);imageFile.delete();jobFile.delete();return;}
        if(mode.equals("qtag"))capabilityInfo.setText("Q-Poll：测试中，等待实际寻卡命令与响应…");
        stopFile=sessionStop;activeMode=mode;powerTagActivated=false;powerDeadline=0;busy=true;terminal=false;stopping=false;sessionStage="connecting";lastAdvice="";resultAction.setVisibility(View.GONE);failureInfo.setVisibility(View.GONE);state.setTextColor(0xff176b61);scanning=false;waitPhase="";main.removeCallbacks(tick);failure.clear();controls();progress.setIndeterminate(true);progress.setVisibility(mode.equals("power")?View.GONE:View.VISIBLE);sessionSteps.setVisibility(mode.equals("refresh")?View.VISIBLE:View.GONE);sessionSteps.setText("准备连接 → 屏幕检查 → 图片传输 → 屏幕刷新");
        if(mode.equals("power"))powerInfo.setText("正在开启供能…");
        chargeTiming.setVisibility(View.GONE);refreshTiming.setVisibility(View.GONE);
        chargeTiming.setText(charge.getSelectedItemPosition()==0?"发送前充能 · 0 秒，跳过额外等待":"发送前充能 · "+charge.getSelectedItemPosition()+" 秒，等待连接屏幕");refreshTiming.setText("发送后上电等待 · "+seconds(powerTimes[power.getSelectedItemPosition()])+" 秒，等待传图完成");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state.setText(manager==2?"等待 Root / NFC":"连接 Shizuku / NFC");detail.setText(launch==3?"前台 App 通信；正在准备 Root HAL 管理通道":launch==2?"请允许 Root 授权；本轮使用旧版 UID 0 通信方式":transport.equals("sn220")?"准备 NXP HAL 兼容层":transport.equals("direct")?"准备直接 Q-Poll":"使用系统 Q-Poll 后端");
        append("──────── "+mode+" · "+(mode.equals("power")?"仅供能，最长 ":"寻卡 ")+seconds+" 秒 ────────");
        final ForegroundNfcSession front=launch==3?new ForegroundNfcSession(this,main,()->resumed&&!destroyed,sessionStop,line->main.post(()->{if(!destroyed)event(line);})):null;
        if(front!=null)foregroundSession=front;
        final android.os.IBinder sessionBinder=bridgeBinder;
        new Thread(()->{try{
            if(front!=null)front.run(mode,seconds,manager,sessionBinder,jobFile);
            else if(launch==0)runBridge(mode,seconds,jobFile,imageFile,sessionStop);
            else runProcessSession(launch,mode,seconds,jobFile,imageFile,sessionStop);
        }catch(Exception e){main.post(()->{if(!destroyed){terminal=true;if(!failure.present())showFailure(e.toString());if(mode.equals("power"))powerInfo.setText("供能执行失败，请查看日志");append("执行失败："+e);}});}
        finally{if(front!=null&&foregroundSession==front){foregroundSession=null;if(destroyed)unbindHalBridge();}sessionStop.delete();jobFile.delete();imageFile.delete();main.post(()->{busy=false;stopping=false;scanning=false;powerDeadline=0;stopWait();activeMode="";progress.setVisibility(View.GONE);chargeTiming.setVisibility(View.GONE);refreshTiming.setVisibility(View.GONE);main.removeCallbacks(tick);if(!destroyed){if(!terminal){terminal=true;showFailure("会话已结束，但未收到结果确认；请查看日志");if(mode.equals("power"))powerInfo.setText("供能会话已结束，请查看日志确认恢复状态");}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);controls();}});}},"panel-session").start();
    }
    private void event(String line){
        if(!line.startsWith("QP|")){if(!line.trim().isEmpty())append(line);return;}
        String[] parts=line.split("\\|",3);if(parts.length!=3)return;String kind=parts[1],message=parts[2];
        if(kind.equals("CHIP"))chipInfo.setText("芯片："+message);
        if(kind.equals("CAPABILITY")||kind.equals("QTAG_REJECTED"))capabilityInfo.setText("Q-Poll："+message);
        if(failure.accept(kind,message)){
            terminal=true;scanning=false;powerDeadline=0;stopWait();main.removeCallbacks(tick);showFailure(failure.message());
            if(activeMode.equals("power"))powerInfo.setText("供能异常："+failure.message());append(kind+" · "+message);return;
        }
        if(failure.present()){
            if(kind.equals("RESTORED")){detail.setText(detail.getText()+" NFC 已恢复普通寻卡。");lastAdvice+="\n\n恢复结果："+message;}
            append(kind+" · "+message);return;
        }
        if(stopping&&(kind.equals("START")||kind.equals("STAGE")||kind.equals("PROGRESS")||kind.equals("TAG")||kind.equals("WAIT")||kind.equals("WAIT_DONE")||kind.equals("POWER_TAG")||kind.equals("POWER_LOST"))){append(kind+" · "+message);return;}
        if(kind.equals("START")){
            sessionStage="scanning";String[] fields=message.split(":");scanning=!fields[0].equals("power");scanDeadline=SystemClock.elapsedRealtime()+Integer.parseInt(fields[1])*1000L;
            if(fields[0].equals("power")){powerDeadline=scanDeadline;state.setText("NFC 仅供能已开启");}else state.setText(fields[0].equals("standard")?"普通 A 寻卡":"Q-Poll 寻卡中");
            main.removeCallbacks(tick);main.post(tick);if(!resumed)requestStop("页面已离开");
        }
        if(kind.equals("POWER_TAG")||kind.equals("POWER_LOST")){powerTagActivated=kind.equals("POWER_TAG");state.setText(powerTagActivated?"仅供能 · 已激活标签":"仅供能 · 等待重新贴合");if(powerDeadline>0)showPowerStatus();}
        if(kind.equals("POWER_DONE")){terminal=true;powerDeadline=0;main.removeCallbacks(tick);state.setText("正在停止供能并恢复 NFC");detail.setText(message);powerInfo.setText(message);}
        if(kind.equals("RESTORED")){
            if(activeMode.equals("power")){terminal=true;powerDeadline=0;main.removeCallbacks(tick);state.setText("供能已关闭 · NFC 已恢复");detail.setText(message);powerInfo.setText("供能已关闭 · NFC 已恢复");}
            if(terminal){lastAdvice+=(lastAdvice.isEmpty()?"":"\n\n")+"恢复结果："+message;resultAction.setVisibility(View.VISIBLE);}
        }
        if(kind.equals("TAG")){scanning=false;main.removeCallbacks(tick);state.setText("收到标签");detail.setText(message);}
        if(kind.equals("STAGE")){
            scanning=false;String[] stage=message.split("\\|",2);sessionStage=stage[0];
            if(!waitPhase.equals("power")||!stage[0].equals("polling")){stopWait();main.removeCallbacks(tick);chargeTiming.setVisibility(View.GONE);refreshTiming.setVisibility(View.GONE);}
            sessionSteps.setText(stage[0].equals("uploading")?"连接 ✓ → 检查 ✓ → 传输中 → 刷新":stage[0].equals("refreshing")||stage[0].equals("polling")?"连接 ✓ → 检查 ✓ → 传输 ✓ → 刷新中":stage[0].equals("charging")?"连接 ✓ → 充能中 → 检查 / 传输 → 刷新":"连接 ✓ → 屏幕检查中 → 传输 → 刷新");
            if(waitPhase.isEmpty()){progress.setIndeterminate(!stage[0].equals("uploading"));if(stage[0].equals("uploading")){progress.setMax(476);progress.setProgress(0);}}progress.setVisibility(View.VISIBLE);
            state.setText(stage.length==2?stage[1]:message);detail.setText("当前贴合的墨水屏 · 保持贴合，不要切换应用。");
        }
        if(kind.equals("WAIT")){String[] fields=message.split("\\|");waitPhase=fields[0];waitStarted=Long.parseLong(fields[1]);waitTotal=Integer.parseInt(fields[2]);main.removeCallbacks(tick);main.post(tick);}
        if(kind.equals("WAIT_DONE")){String[] fields=message.split("\\|");if(waitPhase.equals(fields[0])){showWait(Long.parseLong(fields[1]),true);waitPhase="";main.removeCallbacks(tick);chargeTiming.setVisibility(View.GONE);refreshTiming.setVisibility(View.GONE);}}
        if(kind.equals("PROGRESS")){String[] fields=message.split("\\|",3);int done=Integer.parseInt(fields[0]),total=Integer.parseInt(fields[1]);progress.setIndeterminate(false);progress.setMax(total);progress.setProgress(done);detail.setText("传输已确认 "+done+" / "+total+" 包 · "+(100*done/total)+"% · 保持贴合");syncDeviceFeedback();return;}
        if(kind.equals("READY")){terminal=true;state.setText("检查通过");detail.setText("授权 / NFC 可用；Q-Poll 与完整刷新兼容性仍需实测。");lastAdvice=detail.getText().toString();resultAction.setVisibility(View.VISIBLE);}
        if(kind.equals("IDENTITY"))detail.setText(message);
        if(kind.equals("REFRESH_DONE")||kind.equals("REFRESH_UNCONFIRMED")){
            terminal=true;stopWait();progress.setVisibility(View.GONE);chargeTiming.setVisibility(View.GONE);refreshTiming.setVisibility(View.GONE);sessionSteps.setText("连接 ✓ → 检查 ✓ → 传输 ✓ → 刷新结果请看实物");
            state.setText(kind.equals("REFRESH_DONE")?"刷新周期已结束 · 请看屏幕":"刷新结果待确认");detail.setText(message);lastAdvice=message+"\n请检查实物画面，再决定是否重新发送；不会自动重发。";resultAction.setVisibility(View.VISIBLE);
        }
        if(kind.equals("RESULT")){
            terminal=true;state.setText(message.startsWith("D1")?"屏幕规格校验通过":message.startsWith("已收到")?"寻卡完成":message.startsWith("用户")?"已停止":"未收到标签");detail.setText(message);
            lastAdvice=message.startsWith("D1")||message.startsWith("已收到")||message.startsWith("用户")?message:"本轮未收到标签。请检查贴合位置和授权连接，再手动发起检测；不能据此认定手机不兼容。";resultAction.setVisibility(View.VISIBLE);
        }
        if(kind.equals("CANCELLED")){terminal=true;progress.setVisibility(View.GONE);state.setText(activeMode.equals("power")?"供能已取消":"已停止 · 请检查屏幕画面");detail.setText(message);lastAdvice=message+(activeMode.equals("refresh")?"\n请检查实物画面，再决定是否重新发送。":"");resultAction.setVisibility(View.VISIBLE);if(activeMode.equals("power"))powerInfo.setText(message);}
        append(kind+" · "+message);syncDeviceFeedback();
    }
    private void stopWait(){
        if(waitPhase.isEmpty())return;
        long elapsed=SystemClock.elapsedRealtime()-waitStarted;boolean completed=waitPhase.equals("power")&&elapsed>=waitTotal;
        showWait(completed?waitTotal:elapsed,completed);
        TextView timing=waitPhase.equals("charging")?chargeTiming:refreshTiming;if(!completed)timing.setText(timing.getText()+" · 已中断");waitPhase="";
    }
    private void append(String line){
        String stamped=new SimpleDateFormat("HH:mm:ss.SSS",Locale.ROOT).format(new Date())+"  "+line+"\n";transcript.append(stamped);
        if(transcript.length()>60000)transcript.delete(0,transcript.indexOf("\n",transcript.length()-50000)+1);
        log.setText(transcript.toString());
        try(FileWriter out=new FileWriter(new File(getFilesDir(),"latest.log"),true)){out.write(stamped);}catch(Exception ignored){}
    }
    private void runBridge(String mode,int seconds,File job,File image,File stop)throws Exception {
        android.os.IBinder binder=bridgeBinder;if(binder==null||!binder.isBinderAlive())throw new IOException("请先点击 Shizuku 授权 / 连接。");
        String json="{}";if(job.exists())json=new String(readFile(job),"UTF-8");byte[] bytes=image.exists()?readFile(image):new byte[0];
        Parcel request=Parcel.obtain(),reply=Parcel.obtain();final android.os.Binder owner=new android.os.Binder();
        try{request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");request.writeString(mode);request.writeInt(seconds);request.writeString(json);request.writeByteArray(bytes);request.writeStrongBinder(owner);if(!binder.transact(1,request,reply,0))throw new IOException("后端不兼容");reply.readException();}finally{request.recycle();reply.recycle();}
        boolean cancelSent=false,ended=false;
        try{boolean running=true;long deadline=SystemClock.elapsedRealtime()+335000;
            while(running){if(stop.exists()&&!cancelSent){cancelBridge();cancelSent=true;}
                request=Parcel.obtain();reply=Parcel.obtain();
                try{request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");binder.transact(2,request,reply,0);reply.readException();String logs=reply.readString();running=reply.readInt()!=0;if(logs!=null)for(String line:logs.split("\\n")){final String value=line;main.post(()->{if(!destroyed)event(value);});}}finally{request.recycle();reply.recycle();}
                if(SystemClock.elapsedRealtime()>deadline){cancelBridge();throw new IOException("后端超时，已请求停止。");}if(running)SystemClock.sleep(100);
            }
            ended=true;
        }finally{if(!ended)try{cancelBridge();}catch(Exception ignored){}}
    }
    private void runProcessSession(int launch,String mode,int seconds,File job,File image,File stop)throws Exception {
        String script=BackendPolicy.launchScript(getApplicationInfo().sourceDir,launch==2);
        Process child=launch==2?new ProcessBuilder("su","-c",script).redirectErrorStream(true).start():ShizukuProcess.start(script);
        AtomicBoolean finished=new AtomicBoolean(),ended=new AtomicBoolean(),ready=new AtomicBoolean();
        Thread errors=new Thread(()->{try(BufferedReader in=new BufferedReader(new InputStreamReader(child.getErrorStream(),"UTF-8"))){String line;while((line=in.readLine())!=null){final String value=line;main.post(()->{if(!destroyed)event(value);});}}catch(IOException ignored){}},"backend-stderr");
        errors.setDaemon(true);errors.start();
        Thread input=new Thread(()->{try(OutputStream out=child.getOutputStream()){
            while(!ready.get()&&!finished.get()&&!stop.exists())SystemClock.sleep(100);
            if(finished.get()||stop.exists())return;
            SessionWire.write(out,new SessionWire.Job(mode,seconds,new String(readFile(job),"UTF-8"),image.exists()?readFile(image):new byte[0]));
            long heartbeat=0;
            while(!finished.get()){
                if(stop.exists()){out.write(0);out.flush();return;}
                long now=SystemClock.elapsedRealtime();if(now-heartbeat>=1000){out.write(1);out.flush();heartbeat=now;}
                SystemClock.sleep(100);
            }
        }catch(Exception e){if(!finished.get())main.post(()->{if(!destroyed)append("进程控制管道结束："+e);});}},"backend-owner");
        input.setDaemon(true);input.start();
        Thread deadline=new Thread(()->{long end=SystemClock.elapsedRealtime()+335000,cancelAt=0;
            while(!finished.get()){
                long now=SystemClock.elapsedRealtime();
                if(stop.exists()||now>=end){
                    if(!ready.get()){child.destroy();return;}
                    if(cancelAt==0){cancelAt=now;try{child.getOutputStream().close();}catch(IOException ignored){}}
                    if(now-cancelAt>=35000){child.destroy();main.post(()->{if(!destroyed)event("QP|RESTORE_FAILED|进程停止超时，请关闭再开启 NFC");});return;}
                }
                SystemClock.sleep(100);
            }
        },"backend-deadline");deadline.setDaemon(true);deadline.start();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),"UTF-8"))){
            String line;while((line=in.readLine())!=null){if(line.startsWith("QP|PROCESS_READY|"))ready.set(true);if(line.startsWith("QP|BRIDGE_END|"))ended.set(true);final String value=line;main.post(()->{if(!destroyed)event(value);});}
            int exit=child.waitFor();
            if(!ended.get()){
                if(!ready.get()&&stop.exists())main.post(()->{if(!destroyed)event("QP|CANCELLED|已停止，未启动 NFC 后端");});
                else if(ready.get())main.post(()->{if(!destroyed)event("QP|RESTORE_FAILED|进程异常结束，NFC 恢复未确认，请关闭再开启 NFC");});
                else throw new IOException("进程未启动，退出码="+exit+"；请检查 Root/Shizuku 授权");
            }else if(exit!=0&&!stop.exists())main.post(()->{if(!destroyed)event("QP|PROCESS_EXIT|"+exit);});
        }finally{
            finished.set(true);try{child.getOutputStream().close();}catch(IOException ignored){}
            if(!child.waitFor(35,TimeUnit.SECONDS))child.destroy();
            try{child.getInputStream().close();}catch(IOException ignored){}try{child.getErrorStream().close();}catch(IOException ignored){}
        }
    }
    private static byte[] readFile(File file)throws IOException {if(file.length()>SessionWire.MAX_IMAGE)throw new IOException("任务文件超出限制");byte[] bytes=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(bytes);}return bytes;}
    private void cancelBridge()throws Exception {android.os.IBinder binder=bridgeBinder;if(binder==null)return;Parcel request=Parcel.obtain(),reply=Parcel.obtain();try{request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");binder.transact(3,request,reply,0);reply.readException();}finally{request.recycle();reply.recycle();}}
    private void requestStop(String reason){if(!busy||stopping)return;try{stopping=true;if(!terminal){state.setText("正在停止并恢复 NFC");detail.setText("等待当前通信返回并释放会话，请稍候；不会重发写入。");}main.removeCallbacks(tick);if(stopFile!=null)stopFile.createNewFile();boolean powering=activeMode.equals("power");append(reason+(powering?"；正在关闭供能并恢复 NFC。":"；等当前通信返回后恢复 NFC，不重发写入。"));stop.setEnabled(false);if(powering){powerDeadline=0;main.removeCallbacks(tick);powerSupply.setEnabled(false);powerSupply.setText("正在关闭供能…");powerInfo.setText("正在关闭供能并恢复 NFC…");}}catch(Exception e){append("停止标记失败："+e);}}
    private void showLicense(){
        StringBuilder body=new StringBuilder("参考 https://gitee.com/kur0neko/huawei-epd-pn532\n固定提交 5d3acf730a988815b637262fd4862c1794ab079e\nRequired Notice: Copyright (c) 2026 kur0neko\n\n");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(getAssets().open("licenses/kur0neko-LICENSE.txt"),"UTF-8"))){String line;while((line=in.readLine())!=null)body.append(line).append('\n');}catch(Exception e){body.append(e);}
        TextView t=text(body.toString(),12,0xff263e35);t.setTextIsSelectable(true);t.setPadding(dp(16),dp(10),dp(16),dp(10));ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("PolyForm Noncommercial 1.0.0").setView(s).setPositiveButton("关闭",null).show();
    }
    @Override protected void onResume(){super.onResume();resumed=true;connectShizuku(false);}
    @Override protected void onPause(){ForegroundNfcSession session=foregroundSession;if(session!=null){resumed=false;if(session.readerActive())session.cancel();}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);main.removeCallbacks(saveDraftLater);saveDraft();if(calibration!=null)calibration.saveState(out);}
    @Override protected void onStop(){resumed=false;main.removeCallbacks(saveDraftLater);saveDraft();if(busy)requestStop("页面已离开，自动停止");super.onStop();}
    @Override protected void onDestroy(){main.removeCallbacks(saveDraftLater);saveDraft();draftWorker.shutdown();destroyed=true;revision++;if(launchMode instanceof BackendChoiceSpinner)((BackendChoiceSpinner)launchMode).dismissChoices();if(discoveryBackend instanceof BackendChoiceSpinner)((BackendChoiceSpinner)discoveryBackend).dismissChoices();if(halManager instanceof BackendChoiceSpinner)((BackendChoiceSpinner)halManager).dismissChoices();if(recentDialog!=null)recentDialog.dismiss();if(previewDialog!=null)previewDialog.dismiss();if(calibration!=null)calibration.destroy();if(adjustments!=null)adjustments.destroy();if(busy)requestStop("页面关闭，自动停止");Shizuku.removeRequestPermissionResultListener(permissionResult);Shizuku.removeBinderReceivedListener(binderReceived);Shizuku.removeBinderDeadListener(binderDead);try{Shizuku.unbindUserService(bridgeArgs,bridgeConnection,false);}catch(Exception ignored){}if(foregroundSession==null)unbindHalBridge();main.removeCallbacks(tick);main.removeCallbacks(bridgeTimeout);try{unregisterReceiver(nfcStateReceiver);}catch(Exception ignored){}super.onDestroy();}
}
