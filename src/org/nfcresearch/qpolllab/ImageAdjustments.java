package org.nfcresearch.qpolllab;

/** Non-destructive editing parameters, applied to a fresh raster before palette quantization. */
public final class ImageAdjustments {
    public static final ImageAdjustments NONE=new ImageAdjustments(0,0,0);
    public final int brightness,temperature,contrast;
    public ImageAdjustments(int brightness,int temperature,int contrast){
        if(brightness<-100||brightness>100||temperature<-100||temperature>100||contrast<-100||contrast>100)
            throw new IllegalArgumentException("图像调整范围为 -100 至 +100");
        this.brightness=brightness;this.temperature=temperature;this.contrast=contrast;
    }
    public boolean isNeutral(){return brightness==0&&temperature==0&&contrast==0;}
    public void applyTo(int[] pixels){
        if(isNeutral())return;
        double exposure=Math.pow(2,brightness/50.0),slope=Math.pow(2,contrast/100.0);
        double red=Math.pow(2,temperature/200.0),blue=Math.pow(2,-temperature/200.0);
        double normalization=0.2126*red+0.7152+0.0722*blue;
        int[][] table=new int[3][256];double[] gains={red/normalization,1/normalization,blue/normalization};
        for(int channel=0;channel<3;channel++)for(int value=0;value<256;value++){
            double linear=(0.18+(CalibrationPalette.linear(value)-0.18)*slope)*exposure*gains[channel];
            table[channel][value]=encode(Math.max(0,Math.min(1,linear)));
        }
        for(int i=0;i<pixels.length;i++){
            int p=pixels[i];pixels[i]=(p&0xff000000)|(table[0][(p>>>16)&255]<<16)|(table[1][(p>>>8)&255]<<8)|table[2][p&255];
        }
    }
    private static int encode(double linear){double s=linear<=0.0031308?12.92*linear:1.055*Math.pow(linear,1/2.4)-0.055;return Math.max(0,Math.min(255,(int)Math.round(s*255)));}
    private static String signed(int value){return value>0?"+"+value:Integer.toString(value);}
    public String description(){return "亮度 "+signed(brightness)+" · 色温 "+signed(temperature)+" · 对比度 "+signed(contrast);}
}
