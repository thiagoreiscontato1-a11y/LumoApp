package com.hisho.tether;
import android.graphics.*;import android.media.FaceDetector;import java.io.*;import java.util.*;

/** Curadoria semântica local leve. É heurística/experimental e não substitui revisão humana. */
final class SemanticCurator{
 static final class Result{
  final boolean review;final int penalty;final String note;final int faces;
  Result(boolean review,int penalty,String note,int faces){this.review=review;this.penalty=penalty;this.note=note;this.faces=faces;}
 }
 static Result analyze(File file,int sensitivity)throws IOException{
  BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.toString(),b);if(b.outWidth<32||b.outHeight<32)return new Result(false,0,"",0);
  BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;o.inSampleSize=1;while(Math.max(b.outWidth,b.outHeight)/o.inSampleSize>900)o.inSampleSize*=2;
  Bitmap src=BitmapFactory.decodeFile(file.toString(),o);if(src==null)return new Result(false,0,"",0);
  Bitmap rgb=null;try{
   int w=src.getWidth()&~1,h=src.getHeight();if(w<2)return new Result(false,0,"",0);rgb=src.getConfig()==Bitmap.Config.RGB_565&&src.getWidth()==w?src:Bitmap.createBitmap(src,0,0,w,h).copy(Bitmap.Config.RGB_565,false);
   FaceDetector detector=new FaceDetector(rgb.getWidth(),rgb.getHeight(),8);FaceDetector.Face[] faces=new FaceDetector.Face[8];int n=detector.findFaces(rgb,faces);
   ArrayList<String> reasons=new ArrayList<>();int penalty=0;
   for(int i=0;i<n;i++){FaceDetector.Face f=faces[i];if(f==null)continue;PointF mid=new PointF();f.getMidPoint(mid);float eye=Math.max(1,f.eyesDistance());float left=mid.x-eye*1.7f,right=mid.x+eye*1.7f,top=mid.y-eye*1.5f,bottom=mid.y+eye*2.3f;
    boolean edge=left<rgb.getWidth()*.025f||right>rgb.getWidth()*.975f||top<rgb.getHeight()*.02f||bottom>rgb.getHeight()*.985f;
    if(edge){reasons.add("Pessoa/rosto possivelmente cortado");penalty+=2;}
    if(f.confidence()<.45f&&sensitivity>0){reasons.add("Rosto parcialmente encoberto ou difícil de ler");penalty+=1;}
    double eyeDark=eyeDarkness(rgb,mid.x,mid.y,eye);
    if(eyeDark>.66&&sensitivity>=1){reasons.add("Possível olhos fechados");penalty+=1;}
    double asym=faceSideAsymmetry(rgb,mid.x,mid.y,eye);
    if(asym>.20&&sensitivity>=2){reasons.add("Rosto muito virado ou iluminação facial desigual");penalty+=1;}
    double mouth=mouthAsymmetry(rgb,mid.x,mid.y,eye);
    if(mouth>.22&&sensitivity>=2){reasons.add("Expressão possivelmente desfavorável");penalty+=1;}
   }
   if(n==0&&sensitivity>=2){ // Não reprovamos automaticamente, apenas sinalizamos ausência de rosto em perfil estrito.
    reasons.add("Nenhum rosto detectado");penalty+=0;
   }
   LinkedHashSet<String> unique=new LinkedHashSet<>(reasons);String note=join(unique);boolean review=penalty>=(sensitivity==0?4:sensitivity==1?2:1);
   return new Result(review,Math.min(4,penalty),note,n);
  }finally{if(rgb!=null&&rgb!=src)rgb.recycle();src.recycle();}
 }
 static double eyeDarkness(Bitmap b,float cx,float cy,float eye){int y=(int)(cy-eye*.12f),x1=(int)(cx-eye*.75f),x2=(int)(cx+eye*.75f),r=Math.max(2,(int)(eye*.18f));double d1=patchDark(b,x1,y,r),d2=patchDark(b,x2,y,r);return (d1+d2)/2;}
 static double patchDark(Bitmap b,int cx,int cy,int r){long sum=0;int n=0;for(int y=Math.max(0,cy-r);y<Math.min(b.getHeight(),cy+r);y+=2)for(int x=Math.max(0,cx-r);x<Math.min(b.getWidth(),cx+r);x+=2){int c=b.getPixel(x,y);double l=.2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255);if(l<65)sum++;n++;}return n==0?0:sum/(double)n;}
 static double faceSideAsymmetry(Bitmap b,float cx,float cy,float eye){int r=Math.max(5,(int)(eye*1.1f));double l=0,rr=0;int nl=0,nr=0;for(int y=Math.max(0,(int)cy-r);y<Math.min(b.getHeight(),(int)cy+r);y+=3)for(int x=Math.max(0,(int)cx-r);x<Math.min(b.getWidth(),(int)cx+r);x+=3){int c=b.getPixel(x,y);double v=(.2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255))/255.;if(x<cx){l+=v;nl++;}else{rr+=v;nr++;}}if(nl==0||nr==0)return 0;return Math.abs(l/nl-rr/nr);}
 static double mouthAsymmetry(Bitmap b,float cx,float cy,float eye){int y0=(int)(cy+eye*.55f),h=Math.max(3,(int)(eye*.42f)),w=Math.max(4,(int)(eye*.82f));double l=0,r=0;int nl=0,nr=0;for(int y=Math.max(0,y0-h/2);y<Math.min(b.getHeight(),y0+h/2);y+=2)for(int x=Math.max(0,(int)cx-w);x<Math.min(b.getWidth(),(int)cx+w);x+=2){int c=b.getPixel(x,y);double v=(.2126*((c>>16)&255)+.7152*((c>>8)&255)+.0722*(c&255))/255.;if(x<cx){l+=v;nl++;}else{r+=v;nr++;}}if(nl==0||nr==0)return 0;return Math.abs(l/nl-r/nr);}
 static String join(Collection<String> xs){StringBuilder s=new StringBuilder();for(String x:xs){if(s.length()>0)s.append(" · ");s.append(x);}return s.toString();}
 private SemanticCurator(){}
}
