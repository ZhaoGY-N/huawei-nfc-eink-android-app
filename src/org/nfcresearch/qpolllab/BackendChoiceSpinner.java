package org.nfcresearch.qpolllab;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Spinner;

/** Uses a bounded, scrollable dialog for explanatory choices instead of a ROM's compact spinner popup. */
final class BackendChoiceSpinner extends Spinner {
    private final String title;
    private AlertDialog activeDialog;
    BackendChoiceSpinner(Context context,String title){super(context);this.title=title;}
    @Override public boolean performClick(){
        if(!isEnabled())return false;
        if(activeDialog!=null&&activeDialog.isShowing())return true;
        if(!(getAdapter() instanceof BackendChoiceAdapter))return super.performClick();
        BackendChoiceAdapter choices=(BackendChoiceAdapter)getAdapter();
        BaseAdapter rows=new BaseAdapter(){
            public int getCount(){return choices.getCount();}
            public Object getItem(int position){return choices.getItem(position);}
            public long getItemId(int position){return position;}
            public boolean areAllItemsEnabled(){return false;}
            public boolean isEnabled(int position){return choices.isEnabled(position);}
            public View getView(int position,View convertView,ViewGroup parent){return choices.getDropDownView(position,convertView,parent);}
        };
        android.database.DataSetObserver observer=new android.database.DataSetObserver(){@Override public void onChanged(){rows.notifyDataSetChanged();}@Override public void onInvalidated(){rows.notifyDataSetInvalidated();}};
        final AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(title).setAdapter(rows,(d,position)->{
            if(isEnabled()&&choices.isEnabled(position))setSelection(position);
        }).setNegativeButton("取消",null).create();
        activeDialog=dialog;choices.registerDataSetObserver(observer);dialog.setOnDismissListener(d->{choices.unregisterDataSetObserver(observer);activeDialog=null;});
        dialog.show();return true;
    }
    void dismissChoices(){if(activeDialog!=null)activeDialog.dismiss();}
    @Override protected void onDetachedFromWindow(){dismissChoices();super.onDetachedFromWindow();}
}
