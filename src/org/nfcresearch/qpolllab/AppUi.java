package org.nfcresearch.qpolllab;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** Shared button geometry: 48 dp touch height and an 8 dp gap between neighbors. */
public final class AppUi {
    private AppUi(){}
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    public static LinearLayout.LayoutParams params(Context context,int width,int height,float weight){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,height,weight);
        int inset=dp(context,4);params.setMargins(inset,inset,inset,inset);return params;
    }
    public static Button button(Context context,String label){
        Button button=new Button(context);button.setText(label);button.setAllCaps(false);button.setTextSize(14);
        button.setMinHeight(dp(context,48));button.setMinimumHeight(dp(context,48));button.setPadding(dp(context,12),dp(context,8),dp(context,12),dp(context,8));
        button.setLayoutParams(params(context,-1,-2,0));button.setStateListAnimator(null);button.setElevation(0);
        GradientDrawable background=new GradientDrawable();background.setColor(0xffe7eeea);background.setCornerRadius(dp(context,12));button.setBackground(background);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{0xff263e35,0xff89968f}));return button;
    }
    public static int verticalExtent(View view){
        int height=view.getHeight();if(view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams){android.view.ViewGroup.MarginLayoutParams params=(android.view.ViewGroup.MarginLayoutParams)view.getLayoutParams();height+=params.topMargin+params.bottomMargin;}return height;
    }
}
