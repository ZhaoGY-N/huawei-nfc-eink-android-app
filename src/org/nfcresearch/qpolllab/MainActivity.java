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
import org.json.JSONObject;
import rikka.shizuku.Shizuku;
import android.os.Parcel;

public final class MainActivity extends Activity {
    private final Handler main=new Handler();
    private final ArrayList<View> idleControls=new ArrayList<>();
    private final StringBuilder transcript=new StringBuilder();
    private final int[] durations={5,10,30,60},powerTimes={500,1000,2000,3000,4000,5000},pollTimes={0,10,20,50,100,200,300,400,500},blockSizes={1,2,4,8,16};
    private TextView state,detail,log,imageInfo,thresholdLabel;
    private PixelPreview preview;
    private ProgressBar progress;
    private Button refresh,stop;
    private Button rotateImage,cropImage,restoreImage;
    private Spinner duration,fit,power,poll,blocks,panelType;
    private CheckBox dither,binary;
    private SeekBar threshold;
    private Bitmap source,originalSource;
    private CropWindow appliedCrop;
    private int cropRotation;
    private boolean cropped,editingCrop;
    private boolean calibrationChart;
    private CalibrationUi calibration;
    private PanelImage.Prepared prepared;
    private int rotation,revision;
    private String imageName="内置四色测试图";
    private volatile boolean busy,preparing,resumed,destroyed;
    private volatile File stopFile;
    private Spinner discoveryBackend;
    private TextView authorizationInfo;
    private int bridgeUid=-1;
    private final LinearLayout[] pageBodies=new LinearLayout[3];
    private final ScrollView[] pageScrolls=new ScrollView[3];
    private final Button[] tabs=new Button[3];
    private int activePage;
    private TextView chipInfo,capabilityInfo;
    private android.app.Dialog previewDialog;
    private volatile android.os.IBinder bridgeBinder;
    private final Shizuku.UserServiceArgs bridgeArgs=new Shizuku.UserServiceArgs(new android.content.ComponentName("org.nfcresearch.qpolllab","org.nfcresearch.qpolllab.ShellBridge")).tag("nfc-shell").version(26).daemon(false).processNameSuffix("nfc_shell");
    private final android.content.ServiceConnection bridgeConnection=new android.content.ServiceConnection(){
        public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
            Parcel request=Parcel.obtain(),reply=Parcel.obtain();
            try{request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");if(!binder.transact(4,request,reply,0))throw new IOException("请重新连接新版后端");reply.readException();bridgeUid=reply.readInt();BackendPolicy.validate(bridgeUid,"inspect","system");bridgeBinder=binder;
                String message="Shizuku "+(bridgeUid==0?"Root":"Shell")+" · UID="+bridgeUid+"；NFC 通信使用 Shell 身份";authorizationInfo.setText(message);append(message);
            }catch(Exception e){bridgeBinder=null;bridgeUid=-1;authorizationInfo.setText("连接失败，请停止并重新启动 Shizuku 后重连");append("后端身份检查失败："+e);}finally{request.recycle();reply.recycle();}
        }
        public void onServiceDisconnected(android.content.ComponentName name){bridgeBinder=null;bridgeUid=-1;authorizationInfo.setText("Shizuku 未连接");append("Shizuku 后端已断开。");if(busy)requestStop("授权服务断开");}
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult=(code,result)->{if(code==28){if(result==android.content.pm.PackageManager.PERMISSION_GRANTED)connectShizuku();else append("Shizuku 授权未允许，可在 Shizuku 中重新授权。");}};
    private void connectShizuku(){try{
        if(!Shizuku.pingBinder()){append("请先在 Shizuku 中启动服务，再点击授权 / 连接。");return;}
        if(Shizuku.isPreV11()){append("请升级 Shizuku。");return;}
        if(Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED){Shizuku.requestPermission(28);return;}
        Shizuku.bindUserService(bridgeArgs,bridgeConnection);
    }catch(Exception e){append("Shizuku 连接失败："+e);}}
    private boolean scanning,terminal;
    private final SessionFailure failure=new SessionFailure();
    private long scanDeadline;
    private final Runnable tick=new Runnable(){public void run(){
        if(busy&&scanning){detail.setText("寻找兼容墨水屏 · 最长还剩 "+Math.max(0,(scanDeadline-SystemClock.elapsedRealtime()+999)/1000)+" 秒；保持贴合。");main.postDelayed(this,250);}
    }};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff4f7f5);root.setPadding(dp(16),dp(8),dp(16),0);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title=text("墨水屏",24,0xff142f2a);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button logsButton=button("日志");logsButton.setOnClickListener(v->showLogs());header.addView(logsButton);root.addView(header);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(6),dp(12),dp(8));card.setBackground(round(Color.WHITE));root.addView(card);
        state=text("准备就绪",18,0xff176b61);state.setTypeface(null,Typeface.BOLD);card.addView(state);
        detail=text("先在设备页选择授权方式并检查 NFC。",12,0xff52635d);card.addView(detail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(476);card.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));
        stop=button("停止并恢复 NFC");stop.setEnabled(false);stop.setVisibility(View.GONE);stop.setOnClickListener(v->requestStop("用户停止"));root.addView(stop);
        android.widget.FrameLayout host=new android.widget.FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<3;i++){pageScrolls[i]=new ScrollView(this);pageScrolls[i].setFillViewport(true);pageBodies[i]=new LinearLayout(this);pageBodies[i].setOrientation(LinearLayout.VERTICAL);pageBodies[i].setPadding(0,dp(14),0,dp(24));pageScrolls[i].addView(pageBodies[i]);host.addView(pageScrolls[i],new android.widget.FrameLayout.LayoutParams(-1,-1));}
        LinearLayout nav=new LinearLayout(this);nav.setPadding(0,dp(4),0,dp(8));root.addView(nav);
        String[] names={"设备与检测","图像与发送","高级参数"};for(int i=0;i<3;i++){final int index=i;tabs[i]=button(names[i]);tabs[i].setTextSize(13);tabs[i].setOnClickListener(v->selectPage(index));nav.addView(tabs[i],weighted());}
        Shizuku.addRequestPermissionResultListener(permissionResult);
        LinearLayout page=pageBodies[0];section(page,"设备与 NFC");
        page.addView(text(android.os.Build.MANUFACTURER+" · "+android.os.Build.MODEL+" / "+android.os.Build.DEVICE,14,0xff52635d));
        chipInfo=text("芯片：尚未检查",15,0xff263e35);page.addView(chipInfo);
        capabilityInfo=text("Q-Poll：尚未测试",15,0xff263e35);page.addView(capabilityInfo);
        page.addView(text("芯片信息来自当前系统配置；能力以实际寻卡命令与控制器响应为准。",12,0xff68766e));
        section(page,"Shizuku 授权");
        authorizationInfo=text("Shizuku 未连接",14,0xff52635d);page.addView(authorizationInfo);
        page.addView(action("Shizuku 授权 / 连接",v->connectShizuku()));
        page.addView(text("在 Shizuku 中通过无线调试或 Root 启动服务，再点击授权 / 连接。更换启动方式后重新连接。",12,0xff68766e));
        page.addView(text("Q-Poll 寻卡方式",14,0xff263e35));
        discoveryBackend=spinner(new String[]{"系统 QTag（默认）","SN220 HAL 兼容（实验，需 Root）","直接 Q-Poll（实验，SN100U 待验证）"});page.addView(discoveryBackend);
        page.addView(text("QTag 被拒绝时可手动尝试直接 Q-Poll。直接方式需要可读的 NFC 日志；控制器接受命令后，仍需验证屏幕标签回调。检查 NFC 与普通 NFC-A 对照使用系统方式。",12,0xff68766e));
        page.addView(action("检查芯片 / NFC",v->start("inspect")));
        section(page,"Q-Poll 能力测试");
        page.addView(text("测试会开启短时寻卡，不发送图像或刷新指令。",12,0xff68766e));
        duration=spinner(new String[]{"寻卡 5 秒","寻卡 10 秒","寻卡 30 秒","寻卡 60 秒"});duration.setSelection(1);page.addView(duration);
        page.addView(action("测试 Q-Poll 能力",v->start("qtag")));page.addView(action("普通 NFC-A 对照",v->start("standard")));
        page.addView(text("未读到标签不等于不支持 Q-Poll；成功寻卡也不代表墨水屏供电与刷新已验证。",12,0xff68766e));
        section(page,"屏幕检测");
        page.addView(action("只读检查屏幕",v->start("identity")));
        page.addView(text("读取并核对屏幕规格与原厂配置，不发送图像或启动刷新；无法自动识别 A0 / A1 / A0临时。",12,0xff68766e));
        page=pageBodies[1];section(page,"图像与发送");
        page.addView(text("屏幕类型",14,0xff52635d));
        panelType=spinner(new String[]{"请选择屏幕类型","A0 · 华为 552×768 四色","A1 · 华为 552×768 四色","A0临时 · 左右半屏互换"});
        String savedPanel=getSharedPreferences("refresh-settings",MODE_PRIVATE).getString("panelType","");
        panelType.setSelection(savedPanel.equals("A0")?1:savedPanel.equals("A1")?2:savedPanel.equals("A0X")?3:0);page.addView(panelType);
        page.addView(text("按实物选择 A0 或 A1；A0临时仅用于左右半屏互换的实物。无法自动识别，更换屏幕后请核对选择。",12,0xff68766e));
        preview=new PixelPreview(this);preview.setContentDescription("最终写入像素预览，仅黑白黄红；点击放大 · 双指缩放");page.addView(preview,new LinearLayout.LayoutParams(-1,dp(250)));
        preview.setOnClickListener(v->showPixels());
        refresh=action("发送当前预览到墨水屏",v->confirmRefresh());refresh.setTextColor(Color.WHITE);refresh.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff176b61));page.addView(refresh);
        host.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int height=Math.min(dp(250),Math.max(dp(100),b-t-dp(150)));if(preview.getLayoutParams().height!=height){preview.getLayoutParams().height=height;preview.requestLayout();}});
        page.addView(text("预览仅使用屏幕色板 · 点击放大 · 双指缩放",12,0xff68766e));
        imageInfo=text("正在生成预览…",12,0xff52635d);page.addView(imageInfo);
        section(page,"编辑图片");
        LinearLayout choose=new LinearLayout(this);
        choose.addView(action("选择图片",v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,41);}),weighted());
        choose.addView(action("四色测试图",v->{calibrationChart=false;source=PanelImage.testPattern();originalSource=source;cropped=false;rotation=0;imageName="内置四色测试图";prepare();}),weighted());page.addView(choose);
        page.addView(action("色阶测试图",v->{calibrationChart=false;source=PanelImage.tonePattern();originalSource=source;cropped=false;rotation=0;imageName="内置色阶测试图";prepare();}));
        LinearLayout options=new LinearLayout(this);options.setGravity(android.view.Gravity.CENTER_VERTICAL);
        fit=spinner(new String[]{"完整显示 · 留白","填满屏幕 · 裁剪"});options.addView(fit,new LinearLayout.LayoutParams(0,dp(48),1));
        rotateImage=action("旋转 90°",v->{rotation=(rotation+90)%360;prepare();});options.addView(rotateImage,weighted());page.addView(options);
        LinearLayout cropActions=new LinearLayout(this);
        cropImage=action("裁切图片",v->showCrop());cropActions.addView(cropImage,weighted());
        restoreImage=action("还原原图",v->{if(originalSource!=null){source=originalSource;rotation=0;cropped=false;prepare();}});cropActions.addView(restoreImage,weighted());page.addView(cropActions);
        binary=new CheckBox(this);binary.setText("黑白二值化");binary.setChecked(getSharedPreferences("refresh-settings",MODE_PRIVATE).getBoolean("binary",false));idleControls.add(binary);page.addView(binary);
        int savedThreshold=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("threshold",128);
        thresholdLabel=text("二值化阈值："+Math.max(0,Math.min(255,savedThreshold))+"（松手更新预览）",13,0xff52635d);page.addView(thresholdLabel);
        threshold=new SeekBar(this);threshold.setMax(255);threshold.setProgress(Math.max(0,Math.min(255,savedThreshold)));idleControls.add(threshold);page.addView(threshold);
        page.addView(text("阈值越高，黑色区域越多。可与抖动或图像简化配合。",12,0xff68766e));
        binary.setOnCheckedChangeListener((b,checked)->{getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putBoolean("binary",checked).apply();prepare();});
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean user){thresholdLabel.setText("二值化阈值："+value+"（松手更新预览）");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("threshold",s.getProgress()).apply();prepare();}});
        page.addView(text("图像简化 · 供电紧张时可尝试",14,0xff52635d));
        blocks=spinner(new String[]{"原始细节","轻度 · 2×2 色块","中度 · 4×4 色块","强 · 8×8 色块","极强 · 16×16 色块"});page.addView(blocks);
        int savedBlock=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("blockSize",1),selectedBlock=0;
        for(int i=0;i<blockSizes.length;i++)if(blockSizes[i]==savedBlock)selectedBlock=i;
        blocks.setSelection(selectedBlock);
        page.addView(text("简化后每个色块使用一种颜色，自动关闭抖动；档位越强细节越少。可能改善刷新稳定性，实际效果需测试。",12,0xff68766e));
        dither=new CheckBox(this);dither.setText("抖动处理（细碎色点更多，供电紧张时关闭）");dither.setChecked(selectedBlock==0&&getSharedPreferences("refresh-settings",MODE_PRIVATE).getBoolean("dither",false));idleControls.add(dither);page.addView(dither);
        dither.setOnCheckedChangeListener((b,checked)->{getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putBoolean("dither",checked).apply();prepare();});
        blocks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("blockSize",blockSizes[pos]).apply();if(pos>0)dither.setChecked(false);prepare();}public void onNothingSelected(AdapterView<?> p){}});
        panelType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putString("panelType",selectedPanelType()).apply();prepare();}public void onNothingSelected(AdapterView<?> p){}});
        fit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prepare();}public void onNothingSelected(AdapterView<?> p){}});
        page.addView(text("支持相同原厂配置的 552×768 四色屏。发送时持续贴合并保持应用在前台；切换本应用内的页面不会中断操作。",12,0xff68766e));
        page=pageBodies[2];section(page,"高级参数");
        page.addView(text("参数用于下一次发送，运行期间锁定。",12,0xff68766e));
        section(page,"上电等待时间");power=spinner(new String[]{"500 ms","1000 ms","2000 ms（默认）","3000 ms","4000 ms","5000 ms"});
        int savedPower=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("powerMs",2000),selectedPower=2;for(int i=0;i<powerTimes.length;i++)if(powerTimes[i]==savedPower)selectedPower=i;power.setSelection(selectedPower);page.addView(power);
        page.addView(text("写入刷新指令中的上电等待参数，用于屏幕刷新准备阶段。",12,0xff68766e));
        power.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("powerMs",powerTimes[pos]).apply();}public void onNothingSelected(AdapterView<?> p){}});
        section(page,"状态查询间隔");poll=spinner(new String[]{"0 ms · 连续查询","10 ms","20 ms","50 ms","100 ms","200 ms","300 ms","400 ms","500 ms（默认）"});
        int savedPoll=getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("pollMs",PanelProtocol.DEFAULT_POLL_MS),selectedPoll=pollTimes.length-1;for(int i=0;i<pollTimes.length;i++)if(pollTimes[i]==savedPoll)selectedPoll=i;poll.setSelection(selectedPoll);page.addView(poll);
        poll.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("pollMs",pollTimes[pos]).apply();}public void onNothingSelected(AdapterView<?> p){}});
        page.addView(text("0 ms 表示收到应答后立即再次查询，实际频率取决于通信耗时。",12,0xff68766e));
        page.addView(action("恢复默认参数",v->{power.setSelection(2);poll.setSelection(pollTimes.length-1);}));
        section(page,"屏幕校色");
        calibration=new CalibrationUi(this,new CalibrationUi.Host(){
            public void calibrationChanged(){if(!destroyed)prepare();}
            public void calibrationModal(boolean open){editingCrop=open;if(!destroyed)controls();}
            public void calibrationLoading(boolean loading){preparing=loading;if(!destroyed)controls();}
            public void showCalibrationChart(){
                source=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);int[] pixels=new int[552*768];byte[] codes=CalibrationPalette.chart(552,768);for(int i=0;i<pixels.length;i++)pixels[i]=ImageRaster.color(codes[i]);source.setPixels(pixels,0,552,0,0,552,768);
                originalSource=source;cropped=false;rotation=0;calibrationChart=true;imageName="校色测试图（左上黑、右上白、左下黄、右下红）";selectPage(1);prepare();
            }
        });
        calibration.addTo(page);
        Button about=button("参考项目与许可证");about.setOnClickListener(v->showLicense());page.addView(about);
        page.addView(text("墨水屏刷新测试 · v2.12.0-reader-test1",12,0xff68766e));
        log=text("",11,0xff233c31);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setPadding(dp(10),dp(8),dp(10),dp(8));
        selectPage(getSharedPreferences("refresh-settings",MODE_PRIVATE).getInt("page",0));
        append("v2.12.0-reader-test1 就绪；统一使用 Shizuku Shell / Root 后端。");source=PanelImage.testPattern();originalSource=source;calibration.restore(saved);prepare();
    }
    private void section(LinearLayout page,String title){TextView t=text(title,18,0xff142f2a);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(6));page.addView(t);}
    private void selectPage(int index){activePage=Math.max(0,Math.min(2,index));for(int i=0;i<3;i++){pageScrolls[i].setVisibility(i==activePage?View.VISIBLE:View.GONE);tabs[i].setTextColor(i==activePage?Color.WHITE:0xff52635d);tabs[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(i==activePage?0xff176b61:0xffe3eae6));}getSharedPreferences("refresh-settings",MODE_PRIVATE).edit().putInt("page",activePage).apply();}
    private void showLogs(){if(log.getParent()!=null)((android.view.ViewGroup)log.getParent()).removeView(log);ScrollView scroll=new ScrollView(this);scroll.addView(log);new AlertDialog.Builder(this).setTitle("运行日志").setView(scroll).setNeutralButton("复制",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("墨水屏日志",transcript.toString()));Toast.makeText(this,"日志已复制",Toast.LENGTH_SHORT).show();}).setPositiveButton("关闭",null).show();}
    private LinearLayout.LayoutParams weighted(){return new LinearLayout.LayoutParams(0,dp(48),1);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    private GradientDrawable round(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(12));return g;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));return b;}
    private Button action(String s,View.OnClickListener listener){Button b=button(s);b.setOnClickListener(listener);idleControls.add(b);return b;}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values));idleControls.add(s);return s;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private String selectedPanelType(){int pos=panelType.getSelectedItemPosition();return pos==1?"A0":pos==2?"A1":pos==3?"A0X":"";}
    private void controls(){
        boolean enabled=!busy&&!preparing&&!editingCrop;for(View v:idleControls)v.setEnabled(enabled);
        boolean edit=enabled&&!calibrationChart;
        for(View v:new View[]{rotateImage,cropImage,restoreImage,fit,binary,blocks})v.setEnabled(edit);
        dither.setEnabled(edit&&blocks.getSelectedItemPosition()==0);threshold.setEnabled(edit&&binary.isChecked());
        refresh.setEnabled(enabled&&prepared!=null&&!prepared.panelType.isEmpty());stop.setEnabled(busy);stop.setVisibility(busy?View.VISIBLE:View.GONE);if(calibration!=null)calibration.setEditable(enabled);
    }
    private void prepare(){
        if(source==null||busy||destroyed||editingCrop)return;
        final int token=++revision,angle=rotation,block=blockSizes[blocks.getSelectedItemPosition()],level=threshold.getProgress();final boolean cover=fit.getSelectedItemPosition()==1,noise=dither.isChecked(),mono=binary.isChecked(),hasCrop=cropped;final Bitmap input=source;final String chosenPanel=selectedPanelType();
        final int[] palette=calibration==null?null:calibration.activePalette();final boolean chart=calibrationChart;
        prepared=null;preparing=true;controls();imageInfo.setText("正在生成四色预览…");
        new Thread(()->{try{PanelImage.Prepared image=PanelImage.prepare(input,angle,cover,noise,block,mono,level,hasCrop,chosenPanel,palette,chart);
            main.post(()->{if(token!=revision||destroyed)return;prepared=image;preview.setImage(image.preview);imageInfo.setText(imageName+" · 552×768 · "+image.packed.length+" 字节\n"+image.settings);preparing=false;controls();});
        }catch(Throwable e){main.post(()->{if(token!=revision||destroyed)return;preparing=false;controls();imageInfo.setText("预览失败："+e.getMessage());});}},"image-preview").start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(calibration!=null&&calibration.onActivityResult(request,result,data))return;
        if(request!=41||result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();preparing=true;controls();imageInfo.setText("正在读取图片…");
        new Thread(()->{try{Bitmap image=PanelImage.load(getContentResolver(),uri);main.post(()->{if(destroyed)return;calibrationChart=false;source=image;originalSource=image;cropped=false;rotation=0;imageName="自选图片";preparing=false;prepare();});}
        catch(Throwable e){main.post(()->{if(destroyed)return;preparing=false;controls();imageInfo.setText("图片读取失败："+e.getMessage());});}},"image-load").start();
    }
    private void showPixels(){
        if(prepared==null||busy||preparing||editingCrop)return;
        final Bitmap snapshot=prepared.preview;android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Material_Light_NoActionBar);previewDialog=dialog;dialog.setOnDismissListener(d->previewDialog=null);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setBackgroundColor(0xfff4f7f5);content.setPadding(dp(12),dp(12),dp(12),dp(12));
        content.addView(text("最终发送像素 · 552 × 768",20,0xff142f2a));content.addView(text("双指缩放 · 单指拖动 · 双击切换缩放；校色开启时以近似实物色显示",12,0xff52635d));
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
                Bitmap result=cropView.result();appliedCrop=cropView.snapshot();cropRotation=angle;source=result;rotation=0;cropped=true;calibrationChart=false;
                // Dismiss callbacks can arrive later. Release this guard before prepare(),
                // even when "fill" was already selected and no spinner callback will fire.
                editingCrop=false;
                dialog.dismiss();fit.setSelection(1);prepare();
            }catch(Exception e){Toast.makeText(this,"裁切失败："+e.getMessage(),Toast.LENGTH_LONG).show();}});
        }catch(Exception e){editingCrop=false;controls();Toast.makeText(this,"无法打开裁切："+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void confirmRefresh(){
        if(busy||preparing||prepared==null)return;
        if(prepared.panelType.isEmpty()){Toast.makeText(this,"请先选择屏幕类型 A0、A1 或 A0临时",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("将当前预览写入墨水屏？")
            .setMessage("这会替换当前贴合墨水屏的画面。\n\n目标：相同原厂配置的 552×768 四色屏（不限 UID）\n图片："+imageName+"\n"+prepared.settings+"\n上电等待："+powerTimes[power.getSelectedItemPosition()]+" ms\n忙状态查询间隔："+pollTimes[poll.getSelectedItemPosition()]+" ms\n\n仅贴放要刷新的那块屏。请贴稳，全程保持应用在前台和 NFC 供能，直到日志提示结束。中断可能留下不完整画面。")
            .setNegativeButton("返回预览",null).setPositiveButton("确认并开始刷新",(d,w)->start("refresh")).show();
    }
    private void start(final String mode){
        if(busy||preparing||editingCrop||!resumed)return;
        try{if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED||bridgeBinder==null||!bridgeBinder.isBinderAlive()){connectShizuku();return;}}catch(Exception e){append("请先连接 Shizuku："+e);return;}
        final String transport=BackendPolicy.needsQPoll(mode)?new String[]{"system","sn220","direct"}[discoveryBackend.getSelectedItemPosition()]:"system";
        try{BackendPolicy.validate(bridgeUid,mode,transport);}catch(Exception e){append(e.getMessage());return;}
        final int seconds=durations[duration.getSelectedItemPosition()];
        if(mode.equals("refresh")&&(prepared==null||prepared.panelType.isEmpty())){append("请先在图像与发送页选择屏幕类型 A0、A1 或 A0临时。");return;}
        final String id=Long.toString(SystemClock.elapsedRealtime());
        final File sessionStop=new File(getFilesDir(),"stop-"+id),jobFile=new File(getFilesDir(),"job-"+id+".json"),imageFile=new File(getFilesDir(),"image-"+id+".bin");
        try{
            {JSONObject job=new JSONObject();job.put("discoveryBackend",transport);
                if(mode.equals("refresh")){byte[] image=prepared.packed.clone();try(FileOutputStream out=new FileOutputStream(imageFile)){out.write(image);}String sha=PanelProtocol.toHex(MessageDigest.getInstance("SHA-256").digest(image));
                    job.put("panelType",prepared.panelType);job.put("imageFile",imageFile.getAbsolutePath());job.put("sha256",sha);job.put("powerMs",powerTimes[power.getSelectedItemPosition()]);job.put("pollMs",pollTimes[poll.getSelectedItemPosition()]);append("图片参数："+prepared.settings);append("预览快照 SHA-256="+sha);}
                try(FileOutputStream out=new FileOutputStream(jobFile)){out.write(job.toString().getBytes("UTF-8"));}}
        }catch(Exception e){append("准备失败："+e);imageFile.delete();jobFile.delete();return;}
        if(mode.equals("qtag"))capabilityInfo.setText("Q-Poll：测试中，等待实际寻卡命令与响应…");
        stopFile=sessionStop;busy=true;terminal=false;scanning=false;failure.clear();controls();progress.setProgress(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state.setText("连接 Shizuku / NFC");detail.setText(transport.equals("sn220")?"准备 SN220 兼容层":transport.equals("direct")?"准备直接 Q-Poll 寻卡":"使用系统 NFC 后端");
        append("──────── "+mode+" · 寻卡 "+seconds+" 秒 ────────");
        new Thread(()->{try{
            runBridge(mode,seconds,jobFile,imageFile,sessionStop);
        }catch(Exception e){main.post(()->{if(!destroyed){state.setText("无法执行");append("启动失败："+e);}});}
        finally{sessionStop.delete();jobFile.delete();imageFile.delete();main.post(()->{busy=false;scanning=false;main.removeCallbacks(tick);if(!destroyed){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);controls();}});}},"panel-session").start();
    }
    private void event(String line){
        if(!line.startsWith("QP|")){if(!line.trim().isEmpty())append(line);return;}
        String[] p=line.split("\\|",3);if(p.length!=3)return;String kind=p[1],message=p[2];
        if(kind.equals("CHIP"))chipInfo.setText("芯片："+message);
        if(kind.equals("CAPABILITY"))capabilityInfo.setText("Q-Poll："+message);
        if(kind.equals("QTAG_REJECTED"))capabilityInfo.setText("Q-Poll："+message);
        if(failure.accept(kind,message)){terminal=true;scanning=false;main.removeCallbacks(tick);state.setText("需要检查");detail.setText(failure.message());append(kind+" · "+message);return;}
        if(failure.present()){append(kind+" · "+message);return;}
        if(kind.equals("START")){String[] f=message.split(":");scanning=true;scanDeadline=SystemClock.elapsedRealtime()+Integer.parseInt(f[1])*1000L;state.setText(f[0].equals("standard")?"普通 A 寻卡":"Q-Poll 寻卡中");main.post(tick);if(!resumed)requestStop("页面已离开");}
        if(kind.equals("TAG")){scanning=false;main.removeCallbacks(tick);state.setText("收到标签");detail.setText(message);}
        if(kind.equals("STAGE")){scanning=false;main.removeCallbacks(tick);String[] s=message.split("\\|",2);state.setText(s.length==2?s[1]:message);detail.setText("当前贴合的墨水屏 · 保持贴合，不要切换应用。");}
        if(kind.equals("PROGRESS")){String[] f=message.split("\\|",3);int done=Integer.parseInt(f[0]),total=Integer.parseInt(f[1]);progress.setMax(total);progress.setProgress(done);detail.setText("已确认 "+done+" / "+total+" 包 · "+(100*done/total)+"% · 保持贴合");return;}
        if(kind.equals("READY")){state.setText("检查通过");detail.setText("授权 / NFC 可用；这不代表 Q-Poll 已受支持。");terminal=true;}
        if(kind.equals("IDENTITY"))detail.setText(message);
        if(kind.equals("REFRESH_DONE")||kind.equals("REFRESH_UNCONFIRMED")){terminal=true;state.setText(kind.equals("REFRESH_DONE")?"刷新周期已结束 · 请看屏幕":"刷新结果待确认");detail.setText(message);}
        if(kind.equals("RESULT")){terminal=true;state.setText(message.startsWith("D1")?"屏幕规格校验通过":message.startsWith("已收到")?"寻卡完成":message.startsWith("用户")?"已停止":"未收到标签");detail.setText(message);}
        if(kind.equals("CANCELLED")){terminal=true;state.setText("已停止 · 请检查屏幕画面");detail.setText(message);}
        append(kind+" · "+message);
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
    private static byte[] readFile(File file)throws IOException {byte[] bytes=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(bytes);}return bytes;}
    private void cancelBridge()throws Exception {android.os.IBinder binder=bridgeBinder;if(binder==null)return;Parcel request=Parcel.obtain(),reply=Parcel.obtain();try{request.writeInterfaceToken("org.nfcresearch.qpolllab.NfcSession");binder.transact(3,request,reply,0);reply.readException();}finally{request.recycle();reply.recycle();}}
    private void requestStop(String reason){if(!busy)return;try{if(stopFile!=null)stopFile.createNewFile();append(reason+"；等当前通信返回后恢复 NFC，不重发写入。");stop.setEnabled(false);}catch(Exception e){append("停止标记失败："+e);}}
    private void showLicense(){
        StringBuilder body=new StringBuilder("参考 https://gitee.com/kur0neko/huawei-epd-pn532\n固定提交 5d3acf730a988815b637262fd4862c1794ab079e\nRequired Notice: Copyright (c) 2026 kur0neko\n\n");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(getAssets().open("licenses/kur0neko-LICENSE.txt"),"UTF-8"))){String line;while((line=in.readLine())!=null)body.append(line).append('\n');}catch(Exception e){body.append(e);}
        TextView t=text(body.toString(),12,0xff263e35);t.setTextIsSelectable(true);t.setPadding(dp(16),dp(10),dp(16),dp(10));ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("PolyForm Noncommercial 1.0.0").setView(s).setPositiveButton("关闭",null).show();
    }
    @Override protected void onResume(){super.onResume();resumed=true;}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(calibration!=null)calibration.saveState(out);}
    @Override protected void onStop(){resumed=false;if(busy)requestStop("页面已离开，自动停止");super.onStop();}
    @Override protected void onDestroy(){destroyed=true;revision++;if(previewDialog!=null)previewDialog.dismiss();if(calibration!=null)calibration.destroy();if(busy)requestStop("页面关闭，自动停止");Shizuku.removeRequestPermissionResultListener(permissionResult);try{Shizuku.unbindUserService(bridgeArgs,bridgeConnection,false);}catch(Exception ignored){}main.removeCallbacks(tick);super.onDestroy();}
}
