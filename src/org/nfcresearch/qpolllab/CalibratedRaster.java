package org.nfcresearch.qpolllab;

/** Quantize against measured colours using selectable palette-mixing strategies. */
public final class CalibratedRaster {
    private CalibratedRaster() { }
    private static final double[] LINEAR=new double[256];
    private static final int[][] BAYER8={
        {0,48,12,60,3,51,15,63},{32,16,44,28,35,19,47,31},
        {8,56,4,52,11,59,7,55},{40,24,36,20,43,27,39,23},
        {2,50,14,62,1,49,13,61},{34,18,46,30,33,17,45,29},
        {10,58,6,54,9,57,5,53},{42,26,38,22,41,25,37,21}
    };
    static {for(int i=0;i<256;i++)LINEAR[i]=CalibrationPalette.linear(i);}

    /** Backwards-compatible entry point; keeps the original conversion as the default. */
    public static byte[] quantize(int[] pixels,int width,int height,boolean dither,int block,int[] palette){
        return quantize(pixels,width,height,dither,block,palette,ColorMixPreset.LEGACY_FS);
    }

    public static byte[] quantize(int[] pixels,int width,int height,boolean dither,int block,int[] palette,ColorMixPreset preset){
        CalibrationPalette.validate(palette);
        if(width<=0||height<=0||(long)width*height!=pixels.length||(block!=1&&block!=2&&block!=4&&block!=8&&block!=16))
            throw new IllegalArgumentException("图片参数错误");
        if(preset==null)preset=ColorMixPreset.LEGACY_FS;
        double[][] colors=linearPalette(palette);
        byte[] out=new byte[pixels.length];
        if(block>1){
            // A single code per block cannot express a mixture. Keep the existing
            // block simplification semantics, independent of the selected dither.
            for(int y=0;y<height;y+=block)for(int x=0;x<width;x+=block){
                int right=Math.min(width,x+block),bottom=Math.min(height,y+block),count=(right-x)*(bottom-y);double r=0,g=0,b=0;boolean white=true,black=true;
                for(int yy=y;yy<bottom;yy++)for(int xx=x;xx<right;xx++){
                    int p=pixels[yy*width+xx];r+=LINEAR[(p>>>16)&255];g+=LINEAR[(p>>>8)&255];b+=LINEAR[p&255];white&=(p&0xffffff)==0xffffff;black&=(p&0xffffff)==0;
                }
                r/=count;g/=count;b/=count;boolean neutral=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<0.01;
                byte code=(byte)(white?1:black?0:nearestLinear(target(r,0,colors),target(g,1,colors),target(b,2,colors),colors,neutral?2:4));
                for(int yy=y;yy<bottom;yy++)java.util.Arrays.fill(out,yy*width+x,yy*width+right,code);
            }
            return out;
        }
        switch(preset){
            case LEGACY_FS: return nearestErrorDiffusion(pixels,width,height,dither,colors,false);
            case OKLAB_FS: return nearestErrorDiffusion(pixels,width,height,dither,colors,true);
            case BARYCENTRIC_BAYER: return barycentricBayer(pixels,width,height,dither,colors);
            case BARYCENTRIC_JJN: return barycentricDiffusion(pixels,width,height,dither,colors,true);
            case BARYCENTRIC_NONE: return barycentricDiffusion(pixels,width,height,false,colors,false);
            case BARYCENTRIC_FS:
            default: return barycentricDiffusion(pixels,width,height,dither,colors,false);
        }
    }

    private static double[][] linearPalette(int[] palette){
        double[][] colors=new double[4][3];
        for(int k=0;k<4;k++){colors[k][0]=LINEAR[(palette[k]>>>16)&255];colors[k][1]=LINEAR[(palette[k]>>>8)&255];colors[k][2]=LINEAR[palette[k]&255];}
        return colors;
    }

    private static byte[] nearestErrorDiffusion(int[] pixels,int width,int height,boolean dither,double[][] colors,boolean perceptual){
        byte[] out=new byte[pixels.length];
        double[][] current=new double[3][width+2],next=new double[3][width+2];
        double[][] perceptualColors=perceptual?oklabPalette(colors):null;double[] labScratch=new double[3];
        for(int y=0;y<height;y++){
            int direction=(y%2==0?1:-1);
            for(int step=0;step<width;step++){
                int x=direction==1?step:width-1-step,index=y*width+x,p=pixels[index],rgb=p&0xffffff;
                if(rgb==0||rgb==0xffffff){out[index]=(byte)(rgb==0?0:1);continue;}
                int sr=(p>>>16)&255,sg=(p>>>8)&255,sb=p&255;
                double r=clamp(target(LINEAR[sr],0,colors)+(dither?current[0][x+1]:0));
                double g=clamp(target(LINEAR[sg],1,colors)+(dither?current[1][x+1]:0));
                double b=clamp(target(LINEAR[sb],2,colors)+(dither?current[2][x+1]:0));
                boolean neutral=Math.max(sr,Math.max(sg,sb))-Math.min(sr,Math.min(sg,sb))<=8;
                int best=perceptual?nearestOklab(r,g,b,perceptualColors,neutral?2:4,labScratch):nearestLinear(r,g,b,colors,neutral?2:4);
                out[index]=(byte)best;
                if(dither){
                    spreadFs(current[0],next[0],x,direction,r-colors[best][0]);
                    spreadFs(current[1],next[1],x,direction,g-colors[best][1]);
                    spreadFs(current[2],next[2],x,direction,b-colors[best][2]);
                }
            }
            double[][] old=current;current=next;next=old;for(double[] channel:next)java.util.Arrays.fill(channel,0);
        }
        return out;
    }

    /** Four-colour mixture represented as coverage weights in measured linear RGB. */
    private static byte[] barycentricDiffusion(int[] pixels,int width,int height,boolean dither,double[][] colors,boolean jjn){
        byte[] out=new byte[pixels.length];
        double[][] current=new double[4][width+(jjn?4:2)],next=new double[4][width+(jjn?4:2)],next2=jjn?new double[4][width+4]:null;
        double[] base=new double[4],adjusted=new double[4];MixtureSolver solver=new MixtureSolver(colors);
        for(int y=0;y<height;y++){
            int direction=(y%2==0?1:-1),offset=jjn?2:1;
            for(int step=0;step<width;step++){
                int x=direction==1?step:width-1-step,index=y*width+x,p=pixels[index],rgb=p&0xffffff;
                if(rgb==0||rgb==0xffffff){out[index]=(byte)(rgb==0?0:1);continue;}
                int sr=(p>>>16)&255,sg=(p>>>8)&255,sb=p&255;
                double r=target(LINEAR[sr],0,colors),g=target(LINEAR[sg],1,colors),b=target(LINEAR[sb],2,colors);
                boolean neutral=Math.max(sr,Math.max(sg,sb))-Math.min(sr,Math.min(sg,sb))<=8;
                solver.weights(r,g,b,neutral,base);
                for(int k=0;k<4;k++)adjusted[k]=base[k]+(dither?current[k][x+offset]:0);
                projectSimplex(adjusted);
                int best=maxIndex(adjusted);out[index]=(byte)best;
                if(dither){
                    for(int k=0;k<4;k++){
                        double error=adjusted[k]-(k==best?1.0:0.0);
                        if(jjn)spreadJjn(current[k],next[k],next2[k],x,offset,direction,error);
                        else spreadFs(current[k],next[k],x,direction,error);
                    }
                }
            }
            if(jjn){double[][] old=current;current=next;next=next2;next2=old;for(double[] channel:next2)java.util.Arrays.fill(channel,0);}
            else{double[][] old=current;current=next;next=old;for(double[] channel:next)java.util.Arrays.fill(channel,0);}
        }
        return out;
    }

    private static byte[] barycentricBayer(int[] pixels,int width,int height,boolean dither,double[][] colors){
        byte[] out=new byte[pixels.length];double[] weights=new double[4];MixtureSolver solver=new MixtureSolver(colors);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int index=y*width+x,p=pixels[index],rgb=p&0xffffff;
            if(rgb==0||rgb==0xffffff){out[index]=(byte)(rgb==0?0:1);continue;}
            int sr=(p>>>16)&255,sg=(p>>>8)&255,sb=p&255;
            boolean neutral=Math.max(sr,Math.max(sg,sb))-Math.min(sr,Math.min(sg,sb))<=8;
            solver.weights(target(LINEAR[sr],0,colors),target(LINEAR[sg],1,colors),target(LINEAR[sb],2,colors),neutral,weights);
            if(!dither){out[index]=(byte)maxIndex(weights);continue;}
            double threshold=(BAYER8[y&7][x&7]+0.5)/64.0,cumulative=0;int selected=3;
            for(int k=0;k<4;k++){cumulative+=weights[k];if(threshold<cumulative){selected=k;break;}}
            out[index]=(byte)selected;
        }
        return out;
    }

    /** Fast palette geometry prepared once per image, not once per pixel. */
    private static final class MixtureSolver {
        private final double[][] c;
        private final double[][] inverse=new double[3][3];
        private final boolean tetrahedronValid;
        private final double[] bestWeights=new double[4],candidate=new double[4];
        private double bestError;
        MixtureSolver(double[][] colors){
            c=colors;double[][] m=new double[3][6];
            for(int row=0;row<3;row++){for(int col=0;col<3;col++)m[row][col]=c[col+1][row]-c[0][row];m[row][row+3]=1;}
            boolean valid=true;
            for(int col=0;col<3;col++){
                int pivot=col;for(int row=col+1;row<3;row++)if(Math.abs(m[row][col])>Math.abs(m[pivot][col]))pivot=row;
                if(Math.abs(m[pivot][col])<1e-12){valid=false;break;}
                double[] swap=m[col];m[col]=m[pivot];m[pivot]=swap;double div=m[col][col];for(int j=0;j<6;j++)m[col][j]/=div;
                for(int row=0;row<3;row++)if(row!=col){double factor=m[row][col];for(int j=0;j<6;j++)m[row][j]-=factor*m[col][j];}
            }
            if(valid)for(int row=0;row<3;row++)for(int col=0;col<3;col++)inverse[row][col]=m[row][col+3];
            tetrahedronValid=valid;
        }
        void weights(double r,double g,double b,boolean neutral,double[] out){
            java.util.Arrays.fill(out,0);
            if(neutral){
                double vr=c[1][0]-c[0][0],vg=c[1][1]-c[0][1],vb=c[1][2]-c[0][2];
                double denominator=vr*vr+vg*vg+vb*vb;
                double t=denominator<1e-12?0:((r-c[0][0])*vr+(g-c[0][1])*vg+(b-c[0][2])*vb)/denominator;
                t=clamp(t);out[0]=1-t;out[1]=t;return;
            }
            if(tetrahedronValid){
                double dr=r-c[0][0],dg=g-c[0][1],db=b-c[0][2];
                double w1=inverse[0][0]*dr+inverse[0][1]*dg+inverse[0][2]*db;
                double w2=inverse[1][0]*dr+inverse[1][1]*dg+inverse[1][2]*db;
                double w3=inverse[2][0]*dr+inverse[2][1]*dg+inverse[2][2]*db;
                double w0=1-w1-w2-w3;
                if(w0>=-1e-9&&w1>=-1e-9&&w2>=-1e-9&&w3>=-1e-9){out[0]=Math.max(0,w0);out[1]=Math.max(0,w1);out[2]=Math.max(0,w2);out[3]=Math.max(0,w3);normalize(out);return;}
            }
            bestError=Double.POSITIVE_INFINITY;java.util.Arrays.fill(bestWeights,0);
            // The nearest point outside a tetrahedron lies on one of its faces, edges or vertices.
            considerTriangle(r,g,b,0,1,2);considerTriangle(r,g,b,0,1,3);considerTriangle(r,g,b,0,2,3);considerTriangle(r,g,b,1,2,3);
            considerEdge(r,g,b,0,1);considerEdge(r,g,b,0,2);considerEdge(r,g,b,0,3);considerEdge(r,g,b,1,2);considerEdge(r,g,b,1,3);considerEdge(r,g,b,2,3);
            considerVertex(r,g,b,0);considerVertex(r,g,b,1);considerVertex(r,g,b,2);considerVertex(r,g,b,3);
            System.arraycopy(bestWeights,0,out,0,4);projectSimplex(out);
        }
        private void considerTriangle(double r,double g,double b,int a,int bIndex,int d){
            double v1r=c[bIndex][0]-c[a][0],v1g=c[bIndex][1]-c[a][1],v1b=c[bIndex][2]-c[a][2];
            double v2r=c[d][0]-c[a][0],v2g=c[d][1]-c[a][1],v2b=c[d][2]-c[a][2];
            double aa=v1r*v1r+v1g*v1g+v1b*v1b,ab=v1r*v2r+v1g*v2g+v1b*v2b,bb=v2r*v2r+v2g*v2g+v2b*v2b;
            double det=aa*bb-ab*ab;if(det<1e-12)return;
            double tr=r-c[a][0],tg=g-c[a][1],tb=b-c[a][2];
            double ar=v1r*tr+v1g*tg+v1b*tb,br=v2r*tr+v2g*tg+v2b*tb;
            double u=(ar*bb-br*ab)/det,v=(br*aa-ar*ab)/det,w=1-u-v;
            if(u< -1e-9||v< -1e-9||w< -1e-9)return;
            java.util.Arrays.fill(candidate,0);candidate[a]=Math.max(0,w);candidate[bIndex]=Math.max(0,u);candidate[d]=Math.max(0,v);consider(r,g,b);
        }
        private void considerEdge(double r,double g,double b,int a,int z){
            double vr=c[z][0]-c[a][0],vg=c[z][1]-c[a][1],vb=c[z][2]-c[a][2];double len=vr*vr+vg*vg+vb*vb;if(len<1e-12)return;
            double t=((r-c[a][0])*vr+(g-c[a][1])*vg+(b-c[a][2])*vb)/len;if(t<0||t>1)return;
            java.util.Arrays.fill(candidate,0);candidate[a]=1-t;candidate[z]=t;consider(r,g,b);
        }
        private void considerVertex(double r,double g,double b,int id){java.util.Arrays.fill(candidate,0);candidate[id]=1;consider(r,g,b);}
        private void consider(double r,double g,double b){
            double rr=0,gg=0,bb=0;for(int i=0;i<4;i++){rr+=candidate[i]*c[i][0];gg+=candidate[i]*c[i][1];bb+=candidate[i]*c[i][2];}
            double dr=rr-r,dg=gg-g,db=bb-b,error=dr*dr+dg*dg+db*db;
            if(error<bestError){bestError=error;System.arraycopy(candidate,0,bestWeights,0,4);}
        }
    }
    private static void normalize(double[] values){double total=0;for(double v:values)total+=v;if(total<=1e-12){java.util.Arrays.fill(values,0);values[0]=1;}else for(int i=0;i<values.length;i++)values[i]/=total;}

    private static void projectSimplex(double[] values){
        // Four-item sorting network avoids allocating a temporary array for every pixel.
        double a=values[0],b=values[1],c=values[2],d=values[3],swap;
        if(a<b){swap=a;a=b;b=swap;}if(c<d){swap=c;c=d;d=swap;}
        if(a<c){swap=a;a=c;c=swap;}if(b<d){swap=b;b=d;d=swap;}if(b<c){swap=b;b=c;c=swap;}
        double theta=a-1;
        double candidate=(a+b-1)/2;if(b>candidate)theta=candidate;
        candidate=(a+b+c-1)/3;if(c>candidate)theta=candidate;
        candidate=(a+b+c+d-1)/4;if(d>candidate)theta=candidate;
        double total=0;
        for(int i=0;i<4;i++){values[i]=Math.max(0,values[i]-theta);total+=values[i];}
        if(total<=1e-12){java.util.Arrays.fill(values,0);values[0]=1;}else for(int i=0;i<4;i++)values[i]/=total;
    }

    private static int maxIndex(double[] values){int best=0;for(int i=1;i<values.length;i++)if(values[i]>values[best])best=i;return best;}
    private static void spreadFs(double[] row,double[] next,int x,int direction,double error){row[x+1+direction]+=error*7/16;next[x+1-direction]+=error*3/16;next[x+1]+=error*5/16;next[x+1+direction]+=error/16;}
    private static void spreadJjn(double[] row,double[] next,double[] next2,int x,int offset,int direction,double error){
        int center=x+offset;row[center+direction]+=error*7/48;row[center+2*direction]+=error*5/48;
        next[center-2*direction]+=error*3/48;next[center-direction]+=error*5/48;next[center]+=error*7/48;next[center+direction]+=error*5/48;next[center+2*direction]+=error*3/48;
        next2[center-2*direction]+=error/48;next2[center-direction]+=error*3/48;next2[center]+=error*5/48;next2[center+direction]+=error*3/48;next2[center+2*direction]+=error/48;
    }

    // Work relative to the photographed black/white range, so exposure alone does not clip highlights.
    private static double target(double source,int channel,double[][] palette){return palette[0][channel]+source*(palette[1][channel]-palette[0][channel]);}
    private static int nearestLinear(double r,double g,double b,double[][] palette,int count){
        int best=0;double score=Double.POSITIVE_INFINITY;
        for(int k=0;k<count;k++){double dr=r-palette[k][0],dg=g-palette[k][1],db=b-palette[k][2],s=0.2126*dr*dr+0.7152*dg*dg+0.0722*db*db;if(s<score){score=s;best=k;}}
        return best;
    }

    private static double[][] oklabPalette(double[][] linear){double[][] result=new double[linear.length][3];for(int i=0;i<linear.length;i++)toOklab(linear[i][0],linear[i][1],linear[i][2],result[i]);return result;}
    private static int nearestOklab(double r,double g,double b,double[][] palette,int count,double[] lab){
        toOklab(r,g,b,lab);int best=0;double score=Double.POSITIVE_INFINITY;
        for(int k=0;k<count;k++){double dl=lab[0]-palette[k][0],da=lab[1]-palette[k][1],db=lab[2]-palette[k][2],s=dl*dl+da*da+db*db;if(s<score){score=s;best=k;}}
        return best;
    }
    private static void toOklab(double r,double g,double b,double[] out){
        double l=Math.cbrt(0.4122214708*r+0.5363325363*g+0.0514459929*b);
        double m=Math.cbrt(0.2119034982*r+0.6806995451*g+0.1073969566*b);
        double s=Math.cbrt(0.0883024619*r+0.2817188376*g+0.6299787005*b);
        out[0]=0.2104542553*l+0.7936177850*m-0.0040720468*s;out[1]=1.9779984951*l-2.4285922050*m+0.4505937099*s;out[2]=0.0259040371*l+0.7827717662*m-0.8086757660*s;
    }
    private static double clamp(double n){return Math.max(0,Math.min(1,n));}
}
