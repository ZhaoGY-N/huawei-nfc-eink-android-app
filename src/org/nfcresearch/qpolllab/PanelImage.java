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
        /** Final four-colour raster, exactly matching the payload that will be sent. */
        public final Bitmap preview;
        /** Source image fitted to the same panel frame before colour conversion/adjustments. */
        public final Bitmap originalPreview;
        public final byte[] packed;
        public final String settings;
        public final String panelType;
        Prepared(Bitmap preview,Bitmap originalPreview,byte[] packed,String settings,String panelType){
            this.preview=preview;this.originalPreview=originalPreview;this.packed=packed;this.settings=settings;this.panelType=panelType;
        }
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
    public static Bitmap solidPattern(int code) {
        if(code<0||code>=PALETTE.length)throw new IllegalArgumentException("无效的纯色");
        Bitmap bitmap=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);bitmap.eraseColor(PALETTE[code]);return bitmap;
    }
    /** Fixed multilevel target chart generated from an opaque, deterministic RGB pixel array. */
    public static Bitmap mixingPattern() {
        int[] pixels=TestChartPattern.createPixels();
        // Bitmap.createBitmap(int[], ...) may return an immutable bitmap. Canvas requires
        // a mutable backing store, so allocate a mutable ARGB_8888 bitmap and copy the
        // deterministic source pixels into it before drawing the decorative labels.
        Bitmap bitmap=Bitmap.createBitmap(TestChartPattern.WIDTH,TestChartPattern.HEIGHT,Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels,0,TestChartPattern.WIDTH,0,0,TestChartPattern.WIDTH,TestChartPattern.HEIGHT);
        // Text is decorative only; all chart colours and ramps already exist as exact RGB pixels.
        Canvas canvas=new Canvas(bitmap);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextSize(25);
        canvas.drawText("四色混色 · 多阶对照色卡",18,32,paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);paint.setTextSize(15);
        canvas.drawText("相同目标色卡 · 八组九阶色块与连续渐变",18,55,paint);
        paint.setTextSize(12);
        for(int row=0;row<TestChartPattern.NAMES.length;row++){
            int top=72+row*((TestChartPattern.HEIGHT-134)/8);
            canvas.drawText(TestChartPattern.NAMES[row],17,top+28,paint);
        }
        return bitmap;
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
        return prepare(source,rotation,cover,dither,block,binary,threshold,cropped,panelType,calibrated,chart,ImageAdjustments.NONE);
    }
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType,int[] calibrated,boolean chart,ImageAdjustments adjustments) {
        return prepare(source,rotation,cover,dither,block,binary,threshold,cropped,panelType,calibrated,chart?TestImageMode.CALIBRATION:TestImageMode.NONE,adjustments);
    }
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType,int[] calibrated,TestImageMode mode,ImageAdjustments adjustments) {
        return prepare(source,rotation,cover,dither,block,binary,threshold,cropped,panelType,calibrated,mode,adjustments,ColorMixPreset.LEGACY_FS);
    }
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType,int[] calibrated,TestImageMode mode,ImageAdjustments adjustments,ColorMixPreset mixPreset) {
        return prepare(source,rotation,cover,dither,block,binary,threshold,cropped,panelType,calibrated,mode,adjustments,mixPreset,-1);
    }
    /**
     * @param reinkMode -1 keeps the original project pipeline; 0..3 select the four imported recipes.
     */
    public static Prepared prepare(Bitmap source,int rotation,boolean cover,boolean dither,int block,boolean binary,int threshold,boolean cropped,String panelType,int[] calibrated,TestImageMode mode,ImageAdjustments adjustments,ColorMixPreset mixPreset,int reinkMode) {
        if(source==null||source.isRecycled())throw new IllegalArgumentException("原图不可用");
        if(mode==null)mode=TestImageMode.NONE;
        if(adjustments==null)adjustments=ImageAdjustments.NONE;
        if(mixPreset==null)mixPreset=ColorMixPreset.LEGACY_FS;
        if(reinkMode < -1 || reinkMode >= ReinkRaster.MODES.length)throw new IllegalArgumentException("出图方法无效");
        calibrated=mode.calibrationPalette(calibrated);

        // Generated multilevel charts already are panel-sized target rasters. Do not route
        // them through photo rotation/cover fitting: their source pixels are the test fixture.
        boolean generatedChart=mode==TestImageMode.MIXING||mode==TestImageMode.STYLE_CHART;
        Bitmap fitted;
        if(generatedChart){
            if(source.getWidth()!=552||source.getHeight()!=768)
                throw new IllegalArgumentException("多阶测试色卡必须是 552×768 原始像素");
            fitted=source;
        }else fitted=fitSource(source,rotation,cover);
        Bitmap originalPreview=fitted;

        if(mode.fixedRaster()){
            byte[] codes=mode.fixedCodes(552,768);PanelType type=panel(panelType);byte[] packed=type.pack(codes);
            Bitmap image=Bitmap.createBitmap(type.preview(packed),552,768,Bitmap.Config.ARGB_8888);
            return new Prepared(image,originalPreview,packed,
                (mode==TestImageMode.CALIBRATION?"校色基准图 · 左上黑 / 右上白 / 左下黄 / 右下红":"纯色测试图")+" · 固定四色编码，不使用校色数据，绕过所有图片调整",panelType);
        }

        int[] pixels=new int[552*768];fitted.getPixels(pixels,0,552,0,0,552,768);
        byte[] codes;
        String conversionDescription;

        if(mode==TestImageMode.MIXING){
            // The target card and its mixing quantizer stay fixed, but the selected
            // output-method profile can intentionally tune the RGB input before quantizing.
            int[] outputPalette=calibrated==null?CalibrationPalette.defaults():calibrated;
            int[] exactEndpoints=pixels.clone();
            adjustments.applyTo(pixels);
            codes=CalibratedRaster.quantize(pixels,552,768,true,1,outputPalette,mixPreset);
            TestChartPolicy.preserveExactEndpoints(exactEndpoints,codes);
            TestChartPolicy.requireVisible(codes,"混色算法多阶色卡");
            if(adjustments.isNeutral())TestChartPolicy.requireChromaticCodes(codes,"混色算法多阶色卡");
            PanelType type=panel(panelType);byte[] packed=type.pack(codes);
            Bitmap image=Bitmap.createBitmap(TestChartPolicy.previewPixels(codes,outputPalette),552,768,Bitmap.Config.ARGB_8888);
            String settings="混色算法色卡 · "+mixPreset.title+" · 使用当前校色色板 · 目标图案固定，允许调整亮度 / 色温 / 对比度（"+adjustments.description()+"）";
            settings+="\n四色输出占比："+TestChartPolicy.histogramText(codes);
            settings+="\n当前显示色板："+TestChartPolicy.paletteText(outputPalette);
            return new Prepared(image,originalPreview,packed,settings,panelType);
        }

        boolean chart=mode==TestImageMode.STYLE_CHART;
        ImageAdjustments effectiveAdjustments=adjustments;
        if(chart){binary=false;dither=true;block=1;mixPreset=ColorMixPreset.LEGACY_FS;}
        else if(block>1&&reinkMode<0)dither=false;
        int[] exactEndpoints=chart?pixels.clone():null;
        effectiveAdjustments.applyTo(pixels);
        int[] outputPalette=calibrated==null?(chart?CalibrationPalette.defaults():null):calibrated;
        if(reinkMode>=0){
            if(outputPalette==null)outputPalette=CalibrationPalette.defaults();
            codes=ReinkRaster.quantize(pixels,552,768,reinkMode,outputPalette);
            conversionDescription="出图方法："+ReinkRaster.MODES[reinkMode]+"（"+ReinkRaster.HINTS[reinkMode]+"） · 二值化/色块/手动抖动设置不参与此方法";
        }else if(outputPalette==null||binary){
            codes=ImageRaster.quantize(pixels,552,768,binary,threshold,dither,block);
            conversionDescription=(binary?"黑白二值化 · 阈值 "+threshold:"黑白黄红四色")+" · 简化："+(block==1?"原始细节":block+"×"+block+" 色块")+" · 抖动："+(dither?"开启":"关闭");
        }else{
            codes=CalibratedRaster.quantize(pixels,552,768,dither,block,outputPalette,mixPreset);
            conversionDescription="黑白黄红四色 · 混色算法："+mixPreset.title+" · 简化："+(block==1?"原始细节":block+"×"+block+" 色块")+" · 抖动："+(dither?"开启":"关闭");
        }
        if(chart){
            // The chart has known pure endpoints. Protect them from error carried across
            // adjacent swatches, and fail visibly rather than silently presenting a black frame.
            TestChartPolicy.preserveExactEndpoints(exactEndpoints,codes);
            TestChartPolicy.requireVisible(codes,"四种出图方法多阶色卡");
            if(effectiveAdjustments.isNeutral()&&reinkMode!=ReinkRaster.MODE_INK)
                TestChartPolicy.requireChromaticCodes(codes,"四种出图方法多阶色卡 / "+(reinkMode<0?"原项目算法":ReinkRaster.MODES[reinkMode]));
        }
        PanelType type=panel(panelType);byte[] packed=type.pack(codes);
        int[] appearance=chart?TestChartPolicy.previewPixels(codes,outputPalette):(outputPalette==null?type.preview(packed):type.preview(packed,outputPalette));
        Bitmap image=Bitmap.createBitmap(appearance,552,768,Bitmap.Config.ARGB_8888);
        String settings="屏幕："+(panelType.isEmpty()?"尚未选择":panelType)+" · "+conversionDescription+" · "+(cropped?"已手动裁切 · ":"")+"旋转 "+rotation+"° · "+(cover?"填满":"留白");
        if(chart){
            settings+=" · 固定目标色卡，使用保存的校色色板（未取样时为默认四色）；亮度 / 色温 / 对比度已作用于量化输入";
            settings+="\n四色输出占比："+TestChartPolicy.histogramText(codes);
            settings+="\n当前显示色板："+TestChartPolicy.paletteText(outputPalette);
            if(reinkMode==ReinkRaster.MODE_INK)settings+="\n注意：水墨方法会主动转为 BT.601 灰度，灰色输出属于该方法的预期效果。";
        }else if(mode!=TestImageMode.NONE)settings+=" · 测试图不使用校色数据";
        else settings+=outputPalette==null?" · 校色：关闭":" · 校色：开启（近似实物色；黑白模式仍按阈值）";
        settings+=" · "+effectiveAdjustments.description();
        return new Prepared(image,originalPreview,packed,settings,panelType);
    }

    private static PanelType panel(String value){return value==null||value.isEmpty()?PanelType.A1:PanelType.parse(value);}

    private static Bitmap fitSource(Bitmap source,int rotation,boolean cover){
        Bitmap input=rotated(source,rotation);
        Bitmap fitted=Bitmap.createBitmap(552,768,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(fitted);canvas.drawColor(Color.WHITE);
        float sx=552f/input.getWidth(),sy=768f/input.getHeight(),scale=cover?Math.max(sx,sy):Math.min(sx,sy);
        float w=input.getWidth()*scale,h=input.getHeight()*scale;
        canvas.drawBitmap(input,null,new RectF((552-w)/2,(768-h)/2,(552+w)/2,(768+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
        return fitted;
    }



}
