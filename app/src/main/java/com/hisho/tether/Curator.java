package com.hisho.tether;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/** Curadoria técnica local + score técnico de 1 a 10. */
final class Curator {
 static final class Result {
  final boolean review;
  final String reason;
  final double sharpness, brightness, contrast, clipping, framing;
  final int score;
  Result(boolean review,String reason,double sharpness,double brightness,double contrast,double clipping,double framing,int score){
   this.review=review;this.reason=reason;this.sharpness=sharpness;this.brightness=brightness;this.contrast=contrast;this.clipping=clipping;this.framing=framing;this.score=Math.max(1,Math.min(10,score));
  }
 }

 static Result analyze(File file,int sensitivity)throws IOException{
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
  if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Curadoria não conseguiu ler a imagem editada.");
  int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>720)sample*=2;
  BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=sample;opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
  Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),opt);if(bitmap==null)throw new IOException("Curadoria não conseguiu decodificar a imagem editada.");
  try{return measure(bitmap,Math.max(0,Math.min(2,sensitivity)));}finally{bitmap.recycle();}
 }

 static Result measure(Bitmap b,int sensitivity){
  int w=b.getWidth(),h=b.getHeight();if(w<16||h<16)return new Result(true,"Imagem pequena demais para análise técnica.",0,0,0,1,1,1);
  int[] px=new int[w*h];b.getPixels(px,0,w,0,0,w,h);
  int step=Math.max(1,Math.max(w,h)/640);
  long count=0;double sum=0,sum2=0,clip=0,colorClip=0;
  for(int y=0;y<h;y+=step)for(int x=0;x<w;x+=step){int c=px[y*w+x],r=(c>>16)&255,g=(c>>8)&255,bl=c&255;double l=.2126*r+.7152*g+.0722*bl;sum+=l;sum2+=l*l;count++;if(l<=5||l>=250)clip++;if(r<=2||g<=2||bl<=2||r>=253||g>=253||bl>=253)colorClip++;}
  double mean=sum/Math.max(1,count),variance=Math.max(0,sum2/Math.max(1,count)-mean*mean),contrast=Math.sqrt(variance),clipRatio=Math.max(clip,colorClip)/Math.max(1.0,count);

  double gradSum=0,lapSum=0,lap2=0,energy=0,weightedX=0,weightedY=0;long edgeCount=0;int d=Math.max(1,step);
  for(int y=d;y<h-d;y+=d){for(int x=d;x<w-d;x+=d){int i=y*w+x;double l=lum(px[i]);double left=lum(px[i-d]),right=lum(px[i+d]),up=lum(px[i-d*w]),down=lum(px[i+d*w]);
   double gx=(right-left)/2.0,gy=(down-up)/2.0,grad=Math.sqrt(gx*gx+gy*gy),lap=(left+right+up+down)-4*l;
   gradSum+=grad;lapSum+=lap;lap2+=lap*lap;edgeCount++;double e=grad*grad;energy+=e;weightedX+=e*x;weightedY+=e*y;
  }}
  double meanGrad=gradSum/Math.max(1,edgeCount),lapMean=lapSum/Math.max(1,edgeCount),lapVar=Math.max(0,lap2/Math.max(1,edgeCount)-lapMean*lapMean),sharp=.55*Math.sqrt(lapVar)+.45*meanGrad;
  double cx=energy>0?weightedX/energy:w/2.0,cy=energy>0?weightedY/energy:h/2.0,nx=cx/w,ny=cy/h,edgeDistance=Math.min(Math.min(nx,1-nx),Math.min(ny,1-ny)),framing=Math.max(0,1-edgeDistance/.5);

  double[] sharpMin={7.0,10.5,14.5},contrastMin={13.0,19.0,25.0},darkMin={20.0,30.0,40.0},brightMax={235.0,225.0,215.0},clipMax={.55,.38,.26},frameEdge={.018,.035,.065};
  ArrayList<String> reasons=new ArrayList<>();
  if(sharp<sharpMin[sensitivity])reasons.add("Nitidez baixa/desfoque");
  if(mean<darkMin[sensitivity])reasons.add("Subexposta");
  if(mean>brightMax[sensitivity])reasons.add("Superexposta");
  if(contrast<contrastMin[sensitivity])reasons.add("Contraste muito baixo");
  if(clipRatio>clipMax[sensitivity])reasons.add("Clipping de luz/cor");
  if(sensitivity>0&&edgeDistance<frameEdge[sensitivity])reasons.add("Assunto possivelmente cortado junto à borda");

  int score=score(sharp,mean,contrast,clipRatio,edgeDistance);
  String reason;
  if(reasons.isEmpty())reason=String.format(Locale.ROOT,"Aprovada · score %d/10 · nitidez %.1f · luz %.0f · contraste %.1f",score,sharp,mean,contrast);
  else{StringBuilder out=new StringBuilder();for(int i=0;i<reasons.size();i++){if(i>0)out.append(" · ");out.append(reasons.get(i));}reason=out.toString();}
  return new Result(!reasons.isEmpty(),reason,sharp,mean,contrast,clipRatio,framing,score);
 }

 static int score(double sharp,double mean,double contrast,double clipping,double edgeDistance){
  double s=10.0;
  if(sharp<18)s-=Math.min(4.2,(18-sharp)/4.0);
  double lightPenalty=Math.abs(mean-128)/58.0;s-=Math.min(2.0,lightPenalty);
  if(contrast<30)s-=Math.min(1.6,(30-contrast)/14.0);
  if(clipping>.08)s-=Math.min(1.6,(clipping-.08)*4.5);
  if(edgeDistance<.055)s-=Math.min(1.0,(.055-edgeDistance)*18.0);
  return Math.max(1,Math.min(10,(int)Math.round(s)));
 }
 static double lum(int c){int r=(c>>16)&255,g=(c>>8)&255,b=c&255;return .2126*r+.7152*g+.0722*b;}
 private Curator(){}
}
