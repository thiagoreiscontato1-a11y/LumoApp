package com.hisho.tether;
import android.graphics.*;import android.media.FaceDetector;import java.io.*;import java.util.*;

/**
 * Curadoria por rostos: reprova só quando um rosto principal saiu do quadro (PhotoCheck.cutFraction).
 * As antigas heurísticas de "olhos fechados", "rosto virado" e "expressão" contavam pixels escuros perto dos olhos
 * e da boca: erravam muito (sombra, olhos escuros, pele escura) e foram retiradas. Voltam com um detector de
 * verdade (ML Kit, probabilidade de olho aberto) quando o build migrar para Gradle.
 */
final class SemanticCurator{
 static final class Result{
  final boolean review;final int penalty;final String note;final int faces;
  Result(boolean review,int penalty,String note,int faces){this.review=review;this.penalty=penalty;this.note=note;this.faces=faces;}
 }
 static Result analyze(File file,int sensitivity)throws IOException{
  BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.toString(),b);if(b.outWidth<32||b.outHeight<32)return new Result(false,0,"",0);
  BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;o.inSampleSize=1;while(Math.max(b.outWidth,b.outHeight)/o.inSampleSize>900)o.inSampleSize*=2;
  Bitmap src=BitmapFactory.decodeFile(file.toString(),o);if(src==null)return new Result(false,0,"",0);
  try{return analyze(src,sensitivity);}finally{src.recycle();}
 }
 /** Mesma análise sobre um bitmap já reduzido (~900 px), sem decodificar o JPEG de novo. Não recicla {@code src}. */
 static Result analyze(Bitmap src,int sensitivity){
  if(src.getWidth()<32||src.getHeight()<32)return new Result(false,0,"",0);
  Bitmap rgb=null;try{
   int w=src.getWidth()&~1,h=src.getHeight();
   if(src.getConfig()==Bitmap.Config.RGB_565&&src.getWidth()==w)rgb=src;else{Bitmap crop=w==src.getWidth()?src:Bitmap.createBitmap(src,0,0,w,h);rgb=crop.copy(Bitmap.Config.RGB_565,false);if(crop!=src)crop.recycle();}
   FaceDetector detector=new FaceDetector(rgb.getWidth(),rgb.getHeight(),8);FaceDetector.Face[] faces=new FaceDetector.Face[8];int n=detector.findFaces(rgb,faces);
   int W=rgb.getWidth(),H=rgb.getHeight();double worst=0;
   for(int i=0;i<n;i++){FaceDetector.Face f=faces[i];if(f==null||f.confidence()<.4f)continue;PointF mid=new PointF();f.getMidPoint(mid);float eye=Math.max(1,f.eyesDistance());
    if(PhotoCheck.principal(eye,W,H))worst=Math.max(worst,PhotoCheck.cutFraction(mid.x,mid.y,eye,W,H));}
   boolean review=PhotoCheck.cut(worst,sensitivity);
   String note=review?"Rosto cortado na borda":worst>.05?"Rosto encostado na borda":"";
   return new Result(review,review?2:0,note,n);
  }finally{if(rgb!=null&&rgb!=src)rgb.recycle();}
 }
 static String join(Collection<String> xs){StringBuilder s=new StringBuilder();for(String x:xs){if(s.length()>0)s.append(" · ");s.append(x);}return s.toString();}
 private SemanticCurator(){}
}
