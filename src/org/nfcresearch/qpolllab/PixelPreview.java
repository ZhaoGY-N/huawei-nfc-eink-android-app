package org.nfcresearch.qpolllab;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Draws an immutable prepared raster and an optional source-image comparison overlay. */
public final class PixelPreview extends View {
 private Bitmap image;
 private Bitmap comparisonImage;
 private boolean comparisonVisible;
 private final Paint paint=new Paint();
 private final Paint overlayPaint=new Paint();
 public PixelPreview(Context context){
  super(context);
  paint.setFilterBitmap(false);paint.setDither(false);
  overlayPaint.setFilterBitmap(false);overlayPaint.setDither(false);
 }
 /** Replaces the actual processed preview. This is never called by the hold-to-compare gesture. */
 public void setImage(Bitmap image){this.image=image;invalidate();}
 /** Sets the original source used only as a visual overlay; it never changes the prepared payload. */
 public void setComparisonImage(Bitmap image){this.comparisonImage=image;invalidate();}
 /** Toggles the overlay while keeping the processed raster underneath untouched. */
 public void setComparisonVisible(boolean visible){
  if(comparisonVisible==visible)return;
  comparisonVisible=visible;invalidate();
 }
 public boolean isComparisonVisible(){return comparisonVisible;}
 @Override protected void onDraw(Canvas canvas){
  canvas.drawColor(0xffe3e9e5);
  Bitmap draw=comparisonVisible&&comparisonImage!=null?comparisonImage:image;
  if(draw==null||getWidth()==0||getHeight()==0)return;
  float scale=Math.min(getWidth()/(float)draw.getWidth(),getHeight()/(float)draw.getHeight());
  float w=draw.getWidth()*scale,h=draw.getHeight()*scale;
  RectF target=new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
  canvas.drawBitmap(image==null?draw:image,null,target,paint);
  if(comparisonVisible&&comparisonImage!=null&&image!=null)
   canvas.drawBitmap(comparisonImage,null,target,overlayPaint);
 }
}
