package com.hisho.tether;
import android.graphics.*;import java.io.*;import java.util.*;

final class SmartCurator{
 static final class Result{
  final boolean review;final int score;final String reason,semantic,pHash,duplicateOf;
  Result(boolean review,int score,String reason,String semantic,String pHash,String duplicateOf){this.review=review;this.score=Math.max(1,Math.min(10,score));this.reason=reason;this.semantic=semantic;this.pHash=pHash;this.duplicateOf=duplicateOf;}
 }
 /** Medições da foto editada (técnica, rostos, hash). Só a busca de duplicata depende do banco e do arquivo final. */
 static final class Partial{
  final Curator.Result tech;final SemanticCurator.Result sem;final String pHash;
  Partial(Curator.Result tech,SemanticCurator.Result sem,String pHash){this.tech=tech;this.sem=sem;this.pHash=pHash;}
 }

 /** Lado máximo da versão usada pelos rostos (como a decodificação antiga, em potências de 2). */
 static final int FACE_EDGE=900,TECH_EDGE=720;

 /**
  * Reduz pela metade até caber em {@code maxEdge}. Cada passo tira a média de blocos 2×2, como a
  * decodificação reduzida do JPEG que a curadoria usava. Devolve o próprio {@code src} se já couber.
  */
 static Bitmap shrink(Bitmap src,int maxEdge){
  Bitmap b=src;
  while(Math.max(b.getWidth(),b.getHeight())>maxEdge){Bitmap n=Bitmap.createScaledBitmap(b,Math.max(1,b.getWidth()/2),Math.max(1,b.getHeight()/2),true);if(b!=src)b.recycle();b=n;}
  return b;
 }

 /** Mede a partir da versão ~900 px da foto editada (roda em paralelo com a gravação do JPEG). Não recicla {@code face}. */
 static Partial measure(Bitmap face,int sensitivity){
  int s=Math.max(0,Math.min(2,sensitivity));Bitmap tech=shrink(face,TECH_EDGE);
  try{return new Partial(Curator.measure(tech,s),SemanticCurator.analyze(face,s),PhotoHash.perceptualHash(tech));}
  finally{if(tech!=face)tech.recycle();}
 }

 /** Nota e decisão finais: junta as medições com a busca de duplicatas visuais no evento. */
 static Result finish(Partial p,int sensitivity,Jobs jobs,String id,long size){
  Curator.Result tech=p.tech;SemanticCurator.Result sem=p.sem;String ph=p.pHash,dup=jobs==null?"":jobs.visualDuplicate(id,ph,size);
  int score=tech.score-sem.penalty-(dup.isEmpty()?0:3);score=Math.max(1,Math.min(10,score));
  ArrayList<String> parts=new ArrayList<>();if(tech.review)parts.add(tech.reason);if(!sem.note.isEmpty())parts.add(sem.note);if(!dup.isEmpty())parts.add("Possível duplicata de outra foto");
  boolean review=tech.review||sem.review||(!dup.isEmpty()&&sensitivity>0);
  String reason=parts.isEmpty()?("Aprovada · nota "+score+"/10"):join(parts);
  return new Result(review,score,reason,sem.note,ph,dup);
 }

 /** A partir do arquivo (fotos antigas sem nota): uma só decodificação reduzida para as três medições. */
 static Result analyze(File file,int sensitivity,Jobs jobs,String id)throws Exception{
  BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),b);
  if(b.outWidth<=0||b.outHeight<=0)throw new IOException("Curadoria não conseguiu ler a imagem editada.");
  BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.ARGB_8888;o.inSampleSize=1;while(Math.max(b.outWidth,b.outHeight)/o.inSampleSize>FACE_EDGE)o.inSampleSize*=2;
  Bitmap face=BitmapFactory.decodeFile(file.getAbsolutePath(),o);if(face==null)throw new IOException("Curadoria não conseguiu decodificar a imagem editada.");
  try{return finish(measure(face,sensitivity),sensitivity,jobs,id,file.length());}finally{face.recycle();}
 }
 static String join(List<String> p){StringBuilder s=new StringBuilder();for(String x:p){if(s.length()>0)s.append(" · ");s.append(x);}return s.toString();}
 private SmartCurator(){}
}
