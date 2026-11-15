package org.nfcresearch.qpolllab;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import android.view.MotionEvent;
import android.widget.*;
import java.util.ArrayList;

/** Sliders edit one persistent adjustment profile at a time. */
public final class ImageAdjustmentUi {
    private static final String[] KEYS={"imageBrightness","imageTemperature","imageContrast"};
    private static final String[] NAMES={"亮度","色温","对比度"};
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final Runnable showOriginal;
    private final Runnable restorePreview;
    private final Handler handler=new Handler();
    private final SeekBar[] sliders=new SeekBar[3];
    private final TextView[] labels=new TextView[3];
    private final ArrayList<View> controls=new ArrayList<>();
    private Button compareButton;
    private TextView profileLabel;
    private int profile;
    private boolean editable=true,canCompare,showingOriginal;
    private final Runnable apply=new Runnable(){public void run(){
        if(!editable)return;saveCurrentProfile();changed.run();
    }};

    public ImageAdjustmentUi(Activity activity,int initialProfile,Runnable changed,Runnable showOriginal,Runnable restorePreview){
        this.activity=activity;this.changed=changed;this.showOriginal=showOriginal;this.restorePreview=restorePreview;
        prefs=activity.getSharedPreferences("refresh-settings",Activity.MODE_PRIVATE);
        profile=ImageAdjustmentProfiles.normalize(initialProfile);
    }
    private TextView label(String value){TextView text=new TextView(activity);text.setText(value);text.setTextSize(13);text.setTextColor(0xff52635d);return text;}
    private String key(int index){return ImageAdjustmentProfiles.key(KEYS[index],profile);}
    private void saveCurrentProfile(){
        if(sliders[0]==null)return;
        SharedPreferences.Editor editor=prefs.edit();
        for(int i=0;i<3;i++)editor.putInt(key(i),sliders[i].getProgress()-100);
        editor.apply();
    }
    private void loadCurrentProfile(){
        for(int i=0;i<3;i++){
            if(sliders[i]!=null)sliders[i].setProgress(100+Math.max(-100,Math.min(100,prefs.getInt(key(i),0))));
            updateLabel(i);
        }
        if(profileLabel!=null)profileLabel.setText("当前独立参数配置："+ImageAdjustmentProfiles.name(profile)+"（切换出图方法时自动载入）");
    }
    public void addTo(LinearLayout page){
        profileLabel=label("");page.addView(profileLabel);loadCurrentProfile();
        compareButton=AppUi.button(activity,"按住查看原图");
        compareButton.setEnabled(false);
        compareButton.setContentDescription("按住显示未经色彩处理的原图，松开恢复四色处理预览");
        compareButton.setOnTouchListener((view,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    if(!canCompare)return false;
                    if(view.getParent()!=null)view.getParent().requestDisallowInterceptTouchEvent(true);
                    showingOriginal=true;
                    compareButton.setText("正在显示原图 · 松开恢复");
                    view.setPressed(true);showOriginal.run();
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                case MotionEvent.ACTION_POINTER_UP:
                    finishComparison(view);return true;
                case MotionEvent.ACTION_MOVE:return showingOriginal;
                default:return showingOriginal;
            }
        });
        page.addView(compareButton);
        page.addView(label("按住按钮临时查看未经亮度、色温、对比度及四色量化的原图；松开或中断触摸即恢复处理后预览。"));
        for(int i=0;i<3;i++){
            final int index=i;labels[i]=label("");page.addView(labels[i]);SeekBar bar=new SeekBar(activity);bar.setMax(200);bar.setProgress(100+Math.max(-100,Math.min(100,prefs.getInt(key(i),0))));sliders[i]=bar;controls.add(bar);page.addView(bar);updateLabel(i);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){private boolean dragging;
                public void onProgressChanged(SeekBar s,int progress,boolean user){updateLabel(index);if(user&&!dragging){handler.removeCallbacks(apply);handler.postDelayed(apply,180);}}
                public void onStartTrackingTouch(SeekBar s){dragging=true;handler.removeCallbacks(apply);}
                public void onStopTrackingTouch(SeekBar s){dragging=false;handler.removeCallbacks(apply);apply.run();}
            });
        }
        page.addView(label("松手更新预览；色温向左偏冷、向右偏暖。各出图方法独立保存参数，参数同时作用于预览和发送图片。"));
        Button reset=AppUi.button(activity,"还原当前出图方法的亮度 / 色温 / 对比度");reset.setOnClickListener(v->{handler.removeCallbacks(apply);for(SeekBar bar:sliders)bar.setProgress(100);apply.run();});controls.add(reset);page.addView(reset);
    }
    /** Flush the previous profile before loading the newly selected rendering method. */
    public void setProfile(int nextProfile){
        int next=ImageAdjustmentProfiles.normalize(nextProfile);if(next==profile)return;
        handler.removeCallbacks(apply);saveCurrentProfile();cancelComparison();profile=next;loadCurrentProfile();
    }
    public int profile(){return profile;}
    private void finishComparison(View view){
        if(showingOriginal){showingOriginal=false;if(restorePreview!=null)restorePreview.run();}
        if(compareButton!=null){compareButton.setText("按住查看原图");compareButton.setPressed(false);}
        if(view!=null&&view.getParent()!=null)view.getParent().requestDisallowInterceptTouchEvent(false);
    }
    private void updateLabel(int index){if(sliders[index]==null||labels[index]==null)return;int value=sliders[index].getProgress()-100;String valueText=(value>0?"+":"")+value;String text=NAMES[index]+"："+valueText+(index==1?"（冷 ← 0 → 暖）":"");labels[index].setText(text);sliders[index].setContentDescription(text);}
    public ImageAdjustments snapshot(){return new ImageAdjustments(sliders[0].getProgress()-100,sliders[1].getProgress()-100,sliders[2].getProgress()-100);}
    public void setEditable(boolean value){editable=value;if(!value)handler.removeCallbacks(apply);for(View v:controls)v.setEnabled(value);}
    public void setCanCompare(boolean value){canCompare=value;if(compareButton!=null)compareButton.setEnabled(value);if(!value)cancelComparison();}
    public void cancelComparison(){
        if(showingOriginal){showingOriginal=false;if(restorePreview!=null)restorePreview.run();}
        if(compareButton!=null){compareButton.setText("按住查看原图");compareButton.setPressed(false);if(compareButton.getParent()!=null)compareButton.getParent().requestDisallowInterceptTouchEvent(false);}
    }
    public void destroy(){handler.removeCallbacks(apply);cancelComparison();saveCurrentProfile();}
}
