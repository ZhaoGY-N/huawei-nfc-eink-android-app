package org.nfcresearch.qpolllab;

/** Crop rectangle in source pixels, always inside the source at the panel aspect ratio. */
public final class CropWindow {
    private final double imageWidth,imageHeight,baseWidth,baseHeight;
    private double centerX,centerY,zoom=1;
    public CropWindow(int width,int height) {
        if(width<1||height<1)throw new IllegalArgumentException("图片尺寸错误");
        imageWidth=width;imageHeight=height;baseWidth=Math.min(width,height*552.0/768);baseHeight=baseWidth*768/552;
        reset();
    }
    public CropWindow(CropWindow saved) {
        imageWidth=saved.imageWidth;imageHeight=saved.imageHeight;baseWidth=saved.baseWidth;baseHeight=saved.baseHeight;
        centerX=saved.centerX;centerY=saved.centerY;zoom=saved.zoom;
    }
    public void reset(){zoom=1;centerX=imageWidth/2;centerY=imageHeight/2;}
    public double width(){return baseWidth/zoom;}
    public double height(){return baseHeight/zoom;}
    public double left(){return centerX-width()/2;}
    public double top(){return centerY-height()/2;}
    public void pan(double dx,double dy){centerX+=dx;centerY+=dy;clamp();}
    public void scale(double factor,double focusX,double focusY) {
        if(!Double.isFinite(factor)||factor<=0)return;
        double anchorX=centerX+focusX*width(),anchorY=centerY+focusY*height();
        zoom=Math.max(1,Math.min(8,zoom*factor));
        centerX=anchorX-focusX*width();centerY=anchorY-focusY*height();clamp();
    }
    private void clamp(){centerX=Math.max(width()/2,Math.min(imageWidth-width()/2,centerX));centerY=Math.max(height()/2,Math.min(imageHeight-height()/2,centerY));}
}
