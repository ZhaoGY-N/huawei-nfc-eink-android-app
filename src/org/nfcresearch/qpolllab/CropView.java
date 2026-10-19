package org.nfcresearch.qpolllab;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Move the source beneath a fixed panel-aspect crop frame. */
public final class CropView extends View {
    private final Bitmap image;
    private final CropWindow crop;
    private final RectF frame=new RectF();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector detector;
    private float lastX,lastY;
    public CropView(Context context,Bitmap image,CropWindow saved) {
        super(context);this.image=image;crop=saved==null?new CropWindow(image.getWidth(),image.getHeight()):new CropWindow(saved);
        setContentDescription("裁切图片：单指拖动，双指缩放，白框为保留区域");
        detector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                if(frame.width()>0){crop.scale(d.getScaleFactor(),(d.getFocusX()-frame.centerX())/frame.width(),(d.getFocusY()-frame.centerY())/frame.height());invalidate();}return true;
            }
        });
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float fw=Math.max(1,Math.min(w-32,(h-32)*552f/768)),fh=fw*768/552;frame.set((w-fw)/2,(h-fh)/2,(w+fw)/2,(h+fh)/2);}
    @Override protected void onDraw(Canvas c){
        c.drawColor(0xff252525);float s=(float)(frame.width()/crop.width());
        float left=frame.left-(float)crop.left()*s,top=frame.top-(float)crop.top()*s;
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);c.drawRect(frame,paint);
        c.drawBitmap(image,null,new RectF(left,top,left+image.getWidth()*s,top+image.getHeight()*s),paint);
        paint.setColor(0xaa000000);c.drawRect(0,0,getWidth(),frame.top,paint);c.drawRect(0,frame.bottom,getWidth(),getHeight(),paint);c.drawRect(0,frame.top,frame.left,frame.bottom,paint);c.drawRect(frame.right,frame.top,getWidth(),frame.bottom,paint);
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);c.drawRect(frame,paint);paint.setStrokeWidth(1);paint.setColor(0x88ffffff);
        for(int i=1;i<=2;i++){float x=frame.left+frame.width()*i/3,y=frame.top+frame.height()*i/3;c.drawLine(x,frame.top,x,frame.bottom,paint);c.drawLine(frame.left,y,frame.right,y,paint);}paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        getParent().requestDisallowInterceptTouchEvent(true);boolean scaling=detector.isInProgress();detector.onTouchEvent(event);
        float x=event.getX(),y=event.getY();
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!scaling&&!detector.isInProgress()&&event.getPointerCount()==1&&frame.width()>0){double s=frame.width()/crop.width();crop.pan(-(x-lastX)/s,-(y-lastY)/s);invalidate();}
        lastX=x;lastY=y;
        if(event.getActionMasked()==MotionEvent.ACTION_POINTER_UP&&event.getPointerCount()>1){int remaining=event.getActionIndex()==0?1:0;lastX=event.getX(remaining);lastY=event.getY(remaining);}
        if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    public void reset(){crop.reset();invalidate();}
    public CropWindow snapshot(){return new CropWindow(crop);}
    public Bitmap result(){
        int left=Math.max(0,(int)Math.floor(crop.left())),top=Math.max(0,(int)Math.floor(crop.top()));
        int right=Math.min(image.getWidth(),(int)Math.ceil(crop.left()+crop.width())),bottom=Math.min(image.getHeight(),(int)Math.ceil(crop.top()+crop.height()));
        return Bitmap.createBitmap(image,left,top,Math.max(1,right-left),Math.max(1,bottom-top));
    }
}
