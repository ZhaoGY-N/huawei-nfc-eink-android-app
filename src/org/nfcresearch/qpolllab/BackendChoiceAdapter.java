package org.nfcresearch.qpolllab;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AbsListView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/** Disabled popup rows remain visible with an explicit reason. */
final class BackendChoiceAdapter extends ArrayAdapter<String> {
    interface Details {String description(int position);String blockedReason(int position);}
    private final Details details;
    private int popupContentWidth;
    BackendChoiceAdapter(Context context,String[] titles,Details details){super(context,android.R.layout.simple_spinner_item,titles);this.details=details;}
    void bindTo(Spinner spinner){
        spinner.setAdapter(this);
        // MATCH_PARENT means the anchor's content width, not the longest option's natural width.
        spinner.setDropDownWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        spinner.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            int width=Math.max(1,right-left-spinner.getPaddingLeft()-spinner.getPaddingRight());
            popupContentWidth=width; // A size change must not retrigger backend selection or binding.
        });
    }
    @Override public boolean areAllItemsEnabled(){return false;}
    @Override public boolean isEnabled(int position){return details.blockedReason(position).isEmpty();}
    @Override public View getView(int position,View convertView,ViewGroup parent){
        TextView label=(TextView)super.getView(position,convertView,parent);String reason=details.blockedReason(position);
        boolean prompt=getItem(position).startsWith("请选择");label.setSingleLine(false);label.setHorizontallyScrolling(false);label.setEllipsize(null);label.setMaxLines(Integer.MAX_VALUE);label.setText(getItem(position)+(reason.isEmpty()||prompt?"":"（不可选）"));label.setTextColor(reason.isEmpty()?0xff263e35:0xff8a938f);label.setContentDescription(label.getText()+(reason.isEmpty()?"":"："+reason));return label;
    }
    @Override public View getDropDownView(int position,View convertView,ViewGroup parent){
        final int availableWidth=popupContentWidth>0?popupContentWidth:parent.getWidth()>0?parent.getWidth():getContext().getResources().getDisplayMetrics().widthPixels;
        // Spinner implementations can measure a row with a wider EXACTLY spec than the
        // visible popup. Clamp that spec before TextView builds its wrapping layout.
        LinearLayout row=new LinearLayout(getContext()){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int width=availableWidth,offered=View.MeasureSpec.getSize(widthSpec);
                if(View.MeasureSpec.getMode(widthSpec)!=View.MeasureSpec.UNSPECIFIED&&offered>0)width=Math.min(width,offered);
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.max(1,width),View.MeasureSpec.EXACTLY),heightSpec);
            }
        };row.setOrientation(LinearLayout.VERTICAL);String title=getItem(position),reason=details.blockedReason(position);
        SpannableString text=new SpannableString(title+"\n"+details.description(position)+(reason.isEmpty()?"":"\n不可选："+reason));text.setSpan(new StyleSpan(Typeface.BOLD),0,title.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView label=new TextView(getContext());
        label.setSingleLine(false);label.setHorizontallyScrolling(false);label.setEllipsize(null);label.setMaxLines(Integer.MAX_VALUE);
        row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        label.setMaxWidth(Math.max(1,availableWidth));
        label.setText(text);label.setTextSize(14);float density=getContext().getResources().getDisplayMetrics().density;int horizontal=Math.round(16*density),vertical=Math.round(12*density);row.setPadding(horizontal,vertical,horizontal,vertical);row.setMinimumHeight(Math.round(64*density));label.setLineSpacing(2*density,1);label.setTextColor(reason.isEmpty()?0xff263e35:0xff8a938f);label.setEnabled(reason.isEmpty());row.addView(label,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));row.setEnabled(reason.isEmpty());return row;
    }
}
