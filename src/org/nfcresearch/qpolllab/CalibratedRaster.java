package org.nfcresearch.qpolllab;

/** Quantize against measured colours; mix and diffuse errors in linear RGB. */
public final class CalibratedRaster {
    private CalibratedRaster() { }
    private static final double[] LINEAR=new double[256];
    static {for(int i=0;i<256;i++)LINEAR[i]=CalibrationPalette.linear(i);}
    public static byte[] quantize(int[] pixels,int width,int height,boolean dither,int block,int[] palette){
        CalibrationPalette.validate(palette);
        if(width<=0||height<=0||(long)width*height!=pixels.length||(block!=1&&block!=2&&block!=4&&block!=8&&block!=16))
            throw new IllegalArgumentException("图片参数错误");
        double[][] colors=new double[4][3];
        for(int k=0;k<4;k++){colors[k][0]=LINEAR[(palette[k]>>>16)&255];colors[k][1]=LINEAR[(palette[k]>>>8)&255];colors[k][2]=LINEAR[palette[k]&255];}
        byte[] out=new byte[pixels.length];
        if(block>1){
            for(int y=0;y<height;y+=block)for(int x=0;x<width;x+=block){
                int right=Math.min(width,x+block),bottom=Math.min(height,y+block),count=(right-x)*(bottom-y);double r=0,g=0,b=0;boolean white=true,black=true;
                for(int yy=y;yy<bottom;yy++)for(int xx=x;xx<right;xx++){
                    int p=pixels[yy*width+xx];r+=LINEAR[(p>>>16)&255];g+=LINEAR[(p>>>8)&255];b+=LINEAR[p&255];white&=(p&0xffffff)==0xffffff;black&=(p&0xffffff)==0;
                }
                r/=count;g/=count;b/=count;boolean neutral=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<0.01;
                byte code=(byte)(white?1:black?0:nearest(target(r,0,colors),target(g,1,colors),target(b,2,colors),colors,neutral?2:4));
                for(int yy=y;yy<bottom;yy++)java.util.Arrays.fill(out,yy*width+x,yy*width+right,code);
            }
            return out;
        }
        double[][] current=new double[3][width+2],next=new double[3][width+2];
        for(int y=0;y<height;y++){
            int direction=(y%2==0?1:-1);
            for(int step=0;step<width;step++){
                int x=direction==1?step:width-1-step,index=y*width+x,p=pixels[index],rgb=p&0xffffff;
                // Keep white backgrounds and pure black text free of incoming error speckles.
                if(rgb==0||rgb==0xffffff){out[index]=(byte)(rgb==0?0:1);continue;}
                int sr=(p>>>16)&255,sg=(p>>>8)&255,sb=p&255;
                double r=clamp(target(LINEAR[sr],0,colors)+(dither?current[0][x+1]:0));
                double g=clamp(target(LINEAR[sg],1,colors)+(dither?current[1][x+1]:0));
                double b=clamp(target(LINEAR[sb],2,colors)+(dither?current[2][x+1]:0));
                int best=nearest(r,g,b,colors,Math.max(sr,Math.max(sg,sb))-Math.min(sr,Math.min(sg,sb))<=8?2:4);out[index]=(byte)best;
                if(dither){spread(current[0],next[0],x,direction,r-colors[best][0]);spread(current[1],next[1],x,direction,g-colors[best][1]);spread(current[2],next[2],x,direction,b-colors[best][2]);}
            }
            double[][] old=current;current=next;next=old;for(double[] channel:next)java.util.Arrays.fill(channel,0);
        }
        return out;
    }
    // Work relative to the photographed black/white range, so exposure alone does not clip highlights.
    private static double target(double source,int channel,double[][] palette){return palette[0][channel]+source*(palette[1][channel]-palette[0][channel]);}
    private static int nearest(double r,double g,double b,double[][] palette,int count){
        int best=0;double score=Double.POSITIVE_INFINITY;
        for(int k=0;k<count;k++){double dr=r-palette[k][0],dg=g-palette[k][1],db=b-palette[k][2],s=0.2126*dr*dr+0.7152*dg*dg+0.0722*db*db;if(s<score){score=s;best=k;}}
        return best;
    }
    private static double clamp(double n){return Math.max(0,Math.min(1,n));}
    private static void spread(double[] row,double[] next,int x,int direction,double error){row[x+1+direction]+=error*7/16;next[x+1-direction]+=error*3/16;next[x+1]+=error*5/16;next[x+1+direction]+=error/16;}
}
