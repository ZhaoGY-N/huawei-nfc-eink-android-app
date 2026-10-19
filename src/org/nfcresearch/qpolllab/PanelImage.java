package org.nfcresearch.qpolllab;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.InputStream;

/** Offline preview and 2-bit factory-layout image encoding. */
public final class PanelImage {
    public static final int[] PALETTE={ImageRaster.color(0),ImageRaster.color(1),ImageRaster.color(2),ImageRaster.color(3)};
    public static final class Prepared {
        public final Bitmap preview;
        public final byte[] packed;
        public final String settings;
        public final String panelType;
        Prepared(Bitmap preview,byte[] packed,String settings,String panelType){this.preview=preview;this.packed=packed;this.settings=settings;this.panelType=panelType;}
    }
    public static Bitmap load(ContentResolver resolver,Uri uri) throws Exception {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream in=resolver.openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
        if(options.outWidth<1||options.outHeight<1)throw new IllegalArgumentException("无法识别图片");
        int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>2048)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded;
        try(InputStream in=resolver.openInputStream(uri)){decoded=BitmapFactory.decodeStream(in,null,options);}
        if(decoded==null)throw new IllegalArgumentException("图片解码失败");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=resolver.openInputStream(uri)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}
        catch(Exception ignored) { }
        Matrix m=new Matrix();
        switch(orientation) {
            case 2:m.setScale(-1,1);break;
            case 3:m.setRotate(180);break;
            case 4:m.setScale(1,-1);break;
            case 5:m.setRotate(90);m.postScale(-1,1);break;
            case 6:m.setRotate(90);break;
            case 7:m.setRotate(270);m.postScale(-1,1);break;
            case 8:m.setRotate(270);break;
            default:break;
        }
        return m.isIdentity()?decoded:Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),m,true);
    }
    public static Bitmap testPattern() {
        Bitmap b=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.WHITE);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(44);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText("NFC 刷新测试",44,85,p);p.setTextSize(27);c.drawText("SN220U / Q-Poll",44,132,p);
        for(int i=0;i<4;i++){p.setColor(PALETTE[i]);c.drawRect(i*138,180,(i+1)*138,570,p);}
        p.setColor(Color.BLACK);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);c.drawRect(3,3,549,765,p);p.setStyle(Paint.Style.FILL);
        p.setTextSize(28);c.drawText("552 × 768",44,640,p);c.drawText("顶部 ↑    黑 白 黄 红",44,690,p);
        return b;
    }
    public static Bitmap tonePattern() {
        Bitmap bitmap=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextSize(32);
        canvas.drawText("色阶与抖动测试",24,48,paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);paint.setTextSize(20);
        canvas.drawText("552 × 768 · 从左到右逐渐加深",24,82,paint);
        toneRow(canvas,paint,114,"灰阶 · 16 级 / 连续渐变",Color.BLACK,16,false);
        toneRow(canvas,paint,284,"黄色 · 白 → 黄 → 黑",PALETTE[2],17,true);
        toneRow(canvas,paint,454,"红色 · 白 → 红 → 黑",PALETTE[3],17,true);
        paint.setColor(Color.BLACK);paint.setTextSize(20);canvas.drawText("四色基准",24,620,paint);
        for(int i=0;i<4;i++){
            paint.setColor(PALETTE[i]);canvas.drawRect(24+i*126,634,24+(i+1)*126,680,paint);
        }
        paint.setColor(Color.BLACK);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);
        canvas.drawRect(24,634,528,680,paint);canvas.drawRect(1,1,551,767,paint);paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(20);canvas.drawText("建议：原始细节 · 关闭二值化",24,718,paint);
        canvas.drawText("开启抖动可观察模拟色阶",24,748,paint);
        return bitmap;
    }
    private static void toneRow(Canvas canvas,Paint paint,int top,String label,int color,int steps,boolean throughColor) {
        paint.setColor(Color.BLACK);paint.setTextSize(20);canvas.drawText(label,24,top,paint);
        for(int i=0;i<steps;i++){
            float position=i/(float)(steps-1);
            paint.setColor(throughColor
                ?(position<=0.5f?mix(Color.WHITE,color,position*2):mix(color,Color.BLACK,position*2-1))
                :mix(Color.WHITE,Color.BLACK,position));
            canvas.drawRect(24+i*504f/steps,top+12,24+(i+1)*504f/steps,top+76,paint);
        }
        // Keep the source gradient continuous; the existing quantizer/dither determines output.
        paint.setShader(throughColor
            ?new LinearGradient(24,0,528,0,new int[]{Color.WHITE,color,Color.BLACK},new float[]{0,0.5f,1},Shader.TileMode.CLAMP)
            :new LinearGradient(24,0,528,0,Color.WHITE,Color.BLACK,Shader.TileMode.CLAMP));
        canvas.drawRect(24,top+88,528,top+132,paint);paint.setShader(null);
        paint.setColor(Color.BLACK);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);
        canvas.drawRect(24,top+12,528,top+76,paint);canvas.drawRect(24,top+88,528,top+132,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    private static int mix(int from,int to,float amount) {
        return Color.rgb(Math.round(Color.red(from)+(Color.red(to)-Color.red(from))*amount),
            Math.round(Color.green(from)+(Color.green(to)-Color.green(from))*amount),
            Math.round(Color.blue(from)+(Color.blue(to)-Color.blue(from))*amount));
    }
    public static Bitmap rotated(Bitmap source,int rotation) {
        if(rotation==0)return source;
        Matrix rotate=new Matrix();rotate.setRotate(rotation);
        return Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),rotate,true);
    }
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType) {
        return prepare(source,rotation,cover,dither,block,binary,threshold,cropped,panelType,null,false);
    }
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType,int[] calibrated,boolean chart) {
        if(chart){
            byte[] codes=CalibrationPalette.chart(552,768);PanelType type=panelType.isEmpty()?PanelType.A1:PanelType.parse(panelType);
            byte[] packed=type.pack(codes);
            return new Prepared(Bitmap.createBitmap(type.preview(packed),552,768,Bitmap.Config.ARGB_8888),packed,
                "校色基准图 · 左上黑 / 右上白 / 左下黄 / 右下红 · 固定四色编码，绕过所有图片调整",panelType);
        }
        if(block>1)dither=false;
        Bitmap input=rotated(source,rotation);
        Bitmap fitted=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(fitted);canvas.drawColor(Color.WHITE);
        float sx=552f/input.getWidth(),sy=768f/input.getHeight(),scale=cover?Math.max(sx,sy):Math.min(sx,sy);
        float w=input.getWidth()*scale,h=input.getHeight()*scale;
        canvas.drawBitmap(input,null,new RectF((552-w)/2,(768-h)/2,(552+w)/2,(768+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels=new int[552*768];
        fitted.getPixels(pixels,0,552,0,0,552,768);
        byte[] codes=calibrated==null||binary?ImageRaster.quantize(pixels,552,768,binary,threshold,dither,block)
            :CalibratedRaster.quantize(pixels,552,768,dither,block,calibrated);
        // An unselected panel can still preview; sending requires an explicit selection.
        PanelType type=panelType.isEmpty()?PanelType.A1:PanelType.parse(panelType);
        byte[] packed=type.pack(codes);
        int[] appearance=calibrated==null?type.preview(packed):type.preview(packed,calibrated);
        Bitmap image=Bitmap.createBitmap(appearance,552,768,Bitmap.Config.ARGB_8888);
        String settings="屏幕："+(panelType.isEmpty()?"尚未选择":panelType)+" · "+(binary?"黑白二值化 · 阈值 "+threshold:"黑白黄红四色")+" · 简化："+(block==1?"原始细节":block+"×"+block+" 色块")+" · 抖动："+(dither?"开启":"关闭")+" · "+(cropped?"已手动裁切 · ":"")+"旋转 "+rotation+"° · "+(cover?"填满":"留白");
        settings+=calibrated==null?" · 校色：关闭":" · 校色：开启（近似实物色；黑白模式仍按阈值）";
        return new Prepared(image,packed,settings,panelType);
    }
}
