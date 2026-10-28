package org.nfcresearch.qpolllab;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import java.util.*;

/** One local appearance profile shared by A0/A1, with staged sampling and manual RGB editing. */
public final class CalibrationUi {
    public interface Host {
        void calibrationChanged();
        void calibrationModal(boolean open);
        void calibrationLoading(boolean loading);
        void showCalibrationChart();
    }
    private static final int PICK=51;
    private static final String KEY="calibration.shared.";
    private static final String[] NAMES={"黑","白","黄","红"};
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final ArrayList<View> controls=new ArrayList<>();
    private Dialog activeDialog;
    private final Button[] colors=new Button[4];
    private CheckBox enabled;
    private TextView profile;
    private boolean updating,pendingPhoto,editable=true;

    public CalibrationUi(Activity activity,Host host){this.activity=activity;this.host=host;prefs=activity.getSharedPreferences("refresh-settings",Activity.MODE_PRIVATE);}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private TextView label(String value){TextView t=new TextView(activity);t.setText(value);t.setTextSize(13);t.setTextColor(0xff52635d);t.setPadding(0,dp(5),0,dp(5));return t;}
    private Button button(String value,View.OnClickListener listener){Button b=AppUi.button(activity,value);b.setOnClickListener(listener);return b;}
    private void action(LinearLayout page,String name,View.OnClickListener listener){Button b=button(name,listener);controls.add(b);page.addView(b);}
    private static String hex(int color){return String.format(Locale.ROOT,"#%06X",color&0xffffff);}
    private void swatch(TextView view,String title,int color){view.setText(title+"  "+hex(color));if(view instanceof Button)view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));else view.setBackgroundColor(color);view.setTextColor(CalibrationPalette.luminance(color)>0.35?Color.BLACK:Color.WHITE);}
    public void addTo(LinearLayout page){
        profile=label("");page.addView(profile);
        page.addView(label("无需色卡的相对校色：固定光照，锁定相机曝光与白平衡，关闭 HDR / 滤镜。拍照所得包含相机偏色，可在取样后手调。A0、A1 共用一套配置，保存在本机。"));
        enabled=new CheckBox(activity);enabled.setText("启用校色色板（A0 / A1 共用）");controls.add(enabled);page.addView(enabled);
        enabled.setOnCheckedChangeListener((v,checked)->{if(updating)return;prefs.edit().putBoolean(KEY+"enabled",checked).apply();render();host.calibrationChanged();});
        action(page,"1. 显示校色测试图",v->host.showCalibrationChart());
        page.addView(label("测试图左上黑、右上白、左下黄、右下红。在发送页确认并发送，等待实物刷新完成；可点击“退出校色测试 · 恢复编辑”回到原图片。测试图绕过校色、二值化、抖动和裁切。"));
        action(page,"2. 从相册选择屏幕照片",v->openPhoto());
        page.addView(label("先自行拍摄屏幕，再从相册选入。在照片上分别点选四色块中心并确认取样，避开边缘、阴影和反光。照片仅在本机处理；取样不会替换当前编辑图片。"));
        page.addView(label("3. 手动微调（点击颜色，可调 RGB 或输入十六进制）"));
        for(int i=0;i<4;i++){final int code=i;colors[i]=button("",v->editColor(code));controls.add(colors[i]);page.addView(colors[i]);}
        page.addView(label("手调值描述屏幕实际显示的四色。开启后，转换按取样黑白范围归一化，使用线性 RGB 混色和蛇形误差扩散；预览近似显示实物颜色。黑白二值化仍使用原阈值。关闭校色即可对比默认转换。"));
        action(page,"恢复默认色板并关闭校色",v->{
            prefs.edit().remove(KEY+"colors").putBoolean(KEY+"enabled",false).apply();render();host.calibrationChanged();
        });
        render();
    }
    private int[] read(){
        int[] result=CalibrationPalette.defaults();String stored=prefs.getString(KEY+"colors","");
        if(!stored.isEmpty())try{String[] parts=stored.split(",");if(parts.length!=4)throw new IllegalArgumentException();for(int i=0;i<4;i++)result[i]=Color.parseColor(parts[i]);CalibrationPalette.validate(result);}catch(Exception e){result=CalibrationPalette.defaults();}
        return result;
    }
    public int[] activePalette(){return !prefs.getBoolean(KEY+"enabled",false)?null:read();}
    public void setEditable(boolean value){editable=value;for(View view:controls)view.setEnabled(value);}
    private void render(){
        if(profile==null)return;profile.setText("A0 / A1 共用校色配置 · "+(prefs.getBoolean(KEY+"enabled",false)?"已启用":"未启用"));
        updating=true;enabled.setChecked(prefs.getBoolean(KEY+"enabled",false));updating=false;
        int[] palette=read();for(int i=0;i<4;i++)swatch(colors[i],NAMES[i]+"色 · 手调",palette[i]);setEditable(editable);
    }
    private void save(int[] palette){
        CalibrationPalette.validate(palette);StringBuilder value=new StringBuilder();for(int c:palette){if(value.length()>0)value.append(',');value.append(hex(c));}
        prefs.edit().putString(KEY+"colors",value.toString()).putBoolean(KEY+"enabled",true).apply();render();
    }
    private void editColor(int code){
        final int[] draft=read();final int[] value={draft[code]};
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(4),dp(16),dp(8));
        TextView sample=label("");sample.setMinHeight(dp(48));box.addView(sample);swatch(sample,NAMES[code]+"色",value[0]);
        EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("#RRGGBB");input.setText(hex(value[0]));input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);box.addView(input);
        SeekBar[] channels=new SeekBar[3];TextView[] labels=new TextView[3];String[] names={"R 红","G 绿","B 蓝"};
        for(int i=0;i<3;i++){
            final int channel=i;labels[i]=label(names[i]+"："+((value[0]>>>(16-8*i))&255));box.addView(labels[i]);
            SeekBar bar=new SeekBar(activity);bar.setMax(255);bar.setProgress((value[0]>>>(16-8*i))&255);channels[i]=bar;box.addView(bar);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){
                labels[channel].setText(names[channel]+"："+n);if(!user)return;value[0]=Color.rgb(channels[0].getProgress(),channels[1].getProgress(),channels[2].getProgress());input.setText(hex(value[0]));swatch(sample,NAMES[code]+"色",value[0]);
            }});
        }
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(android.text.Editable text){
            String s=text.toString().trim();if(!s.matches("#?[0-9a-fA-F]{6}"))return;
            value[0]=Color.parseColor(s.startsWith("#")?s:"#"+s);for(int i=0;i<3;i++)channels[i].setProgress((value[0]>>>(16-8*i))&255);swatch(sample,NAMES[code]+"色",value[0]);
        }});
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);host.calibrationModal(true);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("手调"+NAMES[code]+"色 · A0 / A1 共用").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存并启用",null).create();activeDialog=dialog;
        dialog.setOnDismissListener(d->{activeDialog=null;host.calibrationModal(false);host.calibrationChanged();});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            String s=input.getText().toString().trim();if(!s.matches("#?[0-9a-fA-F]{6}"))throw new IllegalArgumentException("请输入六位 RGB，例如 #C80000");
            draft[code]=Color.parseColor(s.startsWith("#")?s:"#"+s);save(draft);dialog.dismiss();
        }catch(IllegalArgumentException e){input.setError(e.getMessage());}});
    }
    private Intent documentPicker(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);return intent;}
    private void openPhoto(){
        pendingPhoto=true;host.calibrationModal(true);
        try{
            if(android.os.Build.VERSION.SDK_INT>=33){
                try{activity.startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"),PICK);}
                catch(ActivityNotFoundException unavailable){activity.startActivityForResult(documentPicker(),PICK);}
            }else activity.startActivityForResult(documentPicker(),PICK);
        }catch(Exception e){pendingPhoto=false;host.calibrationModal(false);host.calibrationChanged();Toast.makeText(activity,"无法打开相册："+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    public void restore(Bundle saved){if(saved==null)return;pendingPhoto=saved.getBoolean("calibrationPendingPhoto",false);if(pendingPhoto)host.calibrationModal(true);}
    public void saveState(Bundle out){out.putBoolean("calibrationPendingPhoto",pendingPhoto);}
    public boolean onActivityResult(int request,int result,Intent data){
        if(request!=PICK)return false;
        final boolean expected=pendingPhoto;pendingPhoto=false;
        Uri uri=data==null?null:data.getData();
        if(result!=Activity.RESULT_OK||uri==null||!expected){host.calibrationModal(false);host.calibrationChanged();return true;}
        host.calibrationLoading(true);
        new Thread(()->{
            Bitmap image=null;String failure=null;try{image=PanelImage.load(activity.getContentResolver(),uri);}catch(Exception|OutOfMemoryError e){failure="无法读取校色照片，请尝试较小的照片";}
            final Bitmap loaded=image;final String error=failure;activity.runOnUiThread(()->{
                if(activity.isDestroyed()||activity.isFinishing()){if(loaded!=null)loaded.recycle();return;}
                host.calibrationLoading(false);host.calibrationModal(false);
                if(loaded!=null)samplePhoto(loaded);else{Toast.makeText(activity,error,Toast.LENGTH_LONG).show();host.calibrationChanged();}
            });
        },"calibration-photo").start();return true;
    }
    private void samplePhoto(Bitmap image){
        final int[] draft=read(),sample={0};final boolean[] taken=new boolean[4],hasSample={false};
        Dialog dialog=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar);activeDialog=dialog;
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(8),dp(12),dp(8));
        content.addView(label("四色取样 · A0 / A1 共用（保存前可重复取样）"));content.addView(label("选颜色 → 点色块中心 → 确认该色。全部四色取样后保存。"));
        Spinner selected=new Spinner(activity);selected.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"黑色","白色","黄色","红色"}));content.addView(selected);
        TextView candidate=label("点选照片中的色块中心");content.addView(candidate);
        Button confirm=button("确认当前颜色取样",null);confirm.setEnabled(false);
        CalibrationPhotoView photo=new CalibrationPhotoView(activity,image,color->{
            if(color==null){hasSample[0]=false;confirm.setEnabled(false);candidate.setBackgroundColor(Color.TRANSPARENT);candidate.setTextColor(0xff52635d);candidate.setText("请在不透明的屏幕色块内取样");return;}
            sample[0]=color;hasSample[0]=true;swatch(candidate,"候选",color);confirm.setEnabled(true);
        });
        content.addView(photo,new LinearLayout.LayoutParams(-1,0,1));content.addView(confirm);
        TextView status=label("");content.addView(status);
        LinearLayout swatches=new LinearLayout(activity);TextView[] samples=new TextView[4];
        for(int i=0;i<4;i++){samples[i]=label(NAMES[i]+"：未取");samples[i].setTextSize(10);samples[i].setGravity(android.view.Gravity.CENTER);swatches.addView(samples[i],new LinearLayout.LayoutParams(0,dp(48),1));}content.addView(swatches);
        LinearLayout actions=new LinearLayout(activity);Button cancel=button("取消",v->dialog.dismiss()),apply=button("保存并启用校色",null);apply.setEnabled(false);actions.addView(cancel,AppUi.params(activity,0,-2,1));actions.addView(apply,AppUi.params(activity,0,-2,1));content.addView(actions);
        selected.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){hasSample[0]=false;confirm.setEnabled(false);candidate.setBackgroundColor(Color.TRANSPARENT);candidate.setTextColor(0xff52635d);candidate.setText("点选"+NAMES[pos]+"色色块中心");}});
        confirm.setOnClickListener(v->{if(!hasSample[0])return;int code=selected.getSelectedItemPosition();draft[code]=sample[0];taken[code]=true;swatch(samples[code],NAMES[code],draft[code]);int count=0,next=-1;for(int i=0;i<4;i++){if(taken[i])count++;else if(next<0)next=i;}status.setText("已确认 "+count+" / 4 色");apply.setEnabled(count==4);if(next>=0)selected.setSelection(next);});
        apply.setOnClickListener(v->{try{save(draft);dialog.dismiss();}catch(IllegalArgumentException e){status.setText(e.getMessage());}});
        host.calibrationModal(true);dialog.setOnDismissListener(d->{activeDialog=null;image.recycle();host.calibrationModal(false);host.calibrationChanged();});dialog.setContentView(content);dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    public void destroy(){if(activeDialog!=null)activeDialog.dismiss();}
}
