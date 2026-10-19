package org.nfcresearch.qpolllab;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;

/** Sliders share one atomic settings snapshot; touch gestures render only on release. */
public final class ImageAdjustmentUi {
    private static final String[] KEYS={"imageBrightness","imageTemperature","imageContrast"};
    private static final String[] NAMES={"亮度","色温","对比度"};
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final Handler handler=new Handler();
    private final SeekBar[] sliders=new SeekBar[3];
    private final TextView[] labels=new TextView[3];
    private final ArrayList<View> controls=new ArrayList<>();
    private boolean editable=true;
    private final Runnable apply=new Runnable(){public void run(){
        if(!editable)return;SharedPreferences.Editor editor=prefs.edit();for(int i=0;i<3;i++)editor.putInt(KEYS[i],sliders[i].getProgress()-100);editor.apply();changed.run();
    }};
    public ImageAdjustmentUi(Activity activity,Runnable changed){this.activity=activity;this.changed=changed;prefs=activity.getSharedPreferences("refresh-settings",Activity.MODE_PRIVATE);}
    private TextView label(String value){TextView text=new TextView(activity);text.setText(value);text.setTextSize(13);text.setTextColor(0xff52635d);return text;}
    public void addTo(LinearLayout page){
        for(int i=0;i<3;i++){
            final int index=i;labels[i]=label("");page.addView(labels[i]);SeekBar bar=new SeekBar(activity);bar.setMax(200);bar.setProgress(100+Math.max(-100,Math.min(100,prefs.getInt(KEYS[i],0))));sliders[i]=bar;controls.add(bar);page.addView(bar);updateLabel(i);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){private boolean dragging;
                public void onProgressChanged(SeekBar s,int progress,boolean user){updateLabel(index);if(user&&!dragging){handler.removeCallbacks(apply);handler.postDelayed(apply,180);}}
                public void onStartTrackingTouch(SeekBar s){dragging=true;handler.removeCallbacks(apply);}
                public void onStopTrackingTouch(SeekBar s){dragging=false;handler.removeCallbacks(apply);apply.run();}
            });
        }
        page.addView(label("松手更新预览；色温向左偏冷、向右偏暖。调整同时作用于预览和发送图片，原图与校色色板保留。"));
        Button reset=new Button(activity);reset.setText("还原亮度 / 色温 / 对比度");reset.setAllCaps(false);reset.setTextSize(14);reset.setOnClickListener(v->{handler.removeCallbacks(apply);for(SeekBar bar:sliders)bar.setProgress(100);apply.run();});controls.add(reset);page.addView(reset);
    }
    private void updateLabel(int index){int value=sliders[index].getProgress()-100;labels[index].setText(NAMES[index]+"："+(value>0?"+":"")+value+(index==1?"（冷 ← 0 → 暖）":""));}
    public ImageAdjustments snapshot(){return new ImageAdjustments(sliders[0].getProgress()-100,sliders[1].getProgress()-100,sliders[2].getProgress()-100);}
    public void setEditable(boolean value){editable=value;if(!value)handler.removeCallbacks(apply);for(View v:controls)v.setEnabled(value);}
    public void destroy(){handler.removeCallbacks(apply);}
}
