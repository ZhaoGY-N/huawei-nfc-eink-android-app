package org.nfcresearch.qpolllab;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Tap/drag a square entirely inside the photographed patch; letterbox taps are ignored. */
public final class CalibrationPhotoView extends View {
    public interface Listener {void sampled(Integer color);}
    private final Bitmap image;
    private final int[] pixels;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds=new RectF();
    private final Listener listener;
    private int cx=-1,cy=-1,radius;
    public CalibrationPhotoView(Context context,Bitmap image,Listener listener){
        super(context);this.image=image;this.listener=listener;
        pixels=new int[image.getWidth()*image.getHeight()];image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
        radius=Math.max(2,Math.min(image.getWidth(),image.getHeight())/100);
        setContentDescription("屏幕照片：点选或拖动取样框到色块中心，避开反光与边缘");
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){float s=Math.min(w/(float)image.getWidth(),h/(float)image.getHeight());float iw=image.getWidth()*s,ih=image.getHeight()*s;bounds.set((w-iw)/2,(h-ih)/2,(w+iw)/2,(h+ih)/2);}
    @Override protected void onDraw(Canvas canvas){
        canvas.drawColor(0xff252525);canvas.drawBitmap(image,null,bounds,paint);
        if(cx>=0){float s=bounds.width()/image.getWidth(),x=bounds.left+(cx+0.5f)*s,y=bounds.top+(cy+0.5f)*s,r=(radius+0.5f)*s;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);paint.setColor(Color.BLACK);canvas.drawRect(x-r,y-r,x+r,y+r,paint);
            paint.setStrokeWidth(2);paint.setColor(Color.WHITE);canvas.drawRect(x-r,y-r,x+r,y+r,paint);canvas.drawLine(x-8,y,x+8,y,paint);canvas.drawLine(x,y-8,x,y+8,paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
            getParent().requestDisallowInterceptTouchEvent(true);
            if(bounds.width()>0&&bounds.contains(e.getX(),e.getY())){
                cx=Math.min(image.getWidth()-1,(int)((e.getX()-bounds.left)*image.getWidth()/bounds.width()));
                cy=Math.min(image.getHeight()-1,(int)((e.getY()-bounds.top)*image.getHeight()/bounds.height()));
                try{listener.sampled(CalibrationPalette.sample(pixels,image.getWidth(),image.getHeight(),cx,cy,radius));}catch(IllegalArgumentException invalid){listener.sampled(null);}
                invalidate();
            }
            if(action==MotionEvent.ACTION_UP)performClick();return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
