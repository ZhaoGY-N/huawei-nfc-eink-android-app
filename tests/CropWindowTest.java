package org.nfcresearch.qpolllab;

import java.util.Random;

public final class CropWindowTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void valid(CropWindow c,int w,int h){double e=1e-7;check(c.width()>0&&c.height()>0,"Empty crop");check(c.left()>=-e&&c.top()>=-e&&c.left()+c.width()<=w+e&&c.top()+c.height()<=h+e,"Crop escaped source");check(Math.abs(c.width()/c.height()-552.0/768)<e,"Aspect ratio changed");}
    public static void main(String[] args){
        Random r=new Random(27);
        for(int[] size:new int[][]{{552,768},{2048,1080},{1080,2048},{1,1},{1,2048},{2048,1}}){
            int w=size[0],h=size[1];CropWindow c=new CropWindow(w,h);valid(c,w,h);
            for(int i=0;i<10000;i++){c.scale(0.5+r.nextDouble()*1.5,r.nextDouble()-0.5,r.nextDouble()-0.5);c.pan((r.nextDouble()-0.5)*w,(r.nextDouble()-0.5)*h);valid(c,w,h);}
            c.reset();valid(c,w,h);check(Math.abs(c.left()+c.width()/2-w/2.0)<1e-7,"Reset center");
        }
        CropWindow c=new CropWindow(2000,2000);c.scale(2,0,0);double anchorX=c.left()+c.width()*0.75,anchorY=c.top()+c.height()*0.75;
        c.scale(1.5,0.25,0.25);check(Math.abs(c.left()+c.width()*0.75-anchorX)<1e-7&&Math.abs(c.top()+c.height()*0.75-anchorY)<1e-7,"Pinch focus drift");
        c.scale(1000,0,0);valid(c,2000,2000);c.scale(Double.NaN,0,0);valid(c,2000,2000);
        CropWindow saved=new CropWindow(c),reopened=new CropWindow(saved);
        check(reopened.left()==c.left()&&reopened.top()==c.top()&&reopened.width()==c.width(),"Reopen lost applied crop");
        double savedLeft=saved.left(),savedWidth=saved.width();
        reopened.scale(0.5,0,0);reopened.pan(100,100);
        check(reopened.width()>savedWidth,"Reopened crop cannot expand into original");
        check(saved.left()==savedLeft&&saved.width()==savedWidth,"Cancelled edits changed applied crop");
        reopened.reset();valid(reopened,2000,2000);
        check(Math.abs(reopened.height()-2000)<1e-7&&saved.width()==savedWidth,"Reset did not restore full source independently");
        System.out.println("CropWindowTest: PASS (60000 pan/zoom bounds, fixed aspect, reset, focus anchor, zoom cap)");
    }
}
