package com.hisho.tether;
import android.graphics.*;import android.media.ExifInterface;import android.util.Xml;import org.xmlpull.v1.XmlPullParser;import org.json.*;import java.io.*;import java.util.*;

/**
 * Edição de fotos do Lumo: decodifica o JPEG, resolve automático + preset XMP + ajuste manual
 * e renderiza tudo de uma vez no {@link Develop} (modelo do pipeline do Lightroom).
 * O ajuste manual é um delta somado aos controles básicos do preset/automático, como mover
 * os sliders do Lightroom depois de aplicar um preset.
 */
final class PhotoEditor {
 static final String[] COLORS=Develop.COLORS;
 static final String[] CONTROLS=Develop.BASIC;

 // ---------------------------------------------------------------- Presets XMP

 static JSONObject parse(String xml)throws Exception{
  if(xml.length()>1024*1024||xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))throw new IOException("XMP inválido ou maior que 1 MB.");
  XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);p.setInput(new StringReader(xml));
  JSONObject result=new JSONObject();for(Map.Entry<String,Double> e:Develop.parse(p).entrySet())result.put(e.getKey(),(double)e.getValue());return result;
 }
 /** Preset salvo (JSON de chaves Camera Raw) como mapa numérico. */
 static Map<String,Double> values(JSONObject preset){
  Map<String,Double> m=new HashMap<>();if(preset==null)return m;Iterator<String> it=preset.keys();
  while(it.hasNext()){String k=it.next();double v=preset.optDouble(k,Double.NaN);if(!Double.isNaN(v))m.put(k,v);}return m;
 }

 // ---------------------------------------------------------------- Controles básicos

 static double limit(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
 static JSONObject controls(double[] v){JSONObject r=new JSONObject();for(int i=0;i<CONTROLS.length;i++)try{r.put(CONTROLS[i],v[i]);}catch(Exception ignored){}return r;}
 static double[] array(JSONObject j){double[] r=new double[CONTROLS.length];if(j!=null)for(int i=0;i<r.length;i++)r[i]=j.optDouble(CONTROLS[i],0);return r;}
 static JSONObject presetControls(JSONObject p){if(p==null)return new JSONObject();double[] r=new double[CONTROLS.length];for(int i=0;i<r.length;i++)r[i]=p.optDouble(Develop.BASIC_XMP[i],0);return controls(r);}
 static JSONObject autoControls(int[] data){return controls(Develop.autoControls(data));}
 static JSONObject sumControls(JSONObject a,JSONObject b){JSONObject r=new JSONObject();for(String k:CONTROLS){double v=(a==null?0:a.optDouble(k,0))+(b==null?0:b.optDouble(k,0));double lo="exposure".equals(k)?-5:-100,hi="exposure".equals(k)?5:100;try{r.put(k,limit(v,lo,hi));}catch(Exception ignored){}}return r;}
 static JSONObject scaleControls(JSONObject a,double strength){JSONObject r=new JSONObject();if(a==null)return r;Iterator<String> it=a.keys();while(it.hasNext()){String k=it.next();try{r.put(k,a.optDouble(k,0)*strength);}catch(Exception ignored){}}return r;}
 /** Automático da foto, já escalado pela intensidade das opções (vazio se desligado). */
 static JSONObject autoValues(Develop.Source source,JSONObject options){return options.optBoolean("auto",true)?scaleControls(controls(source.autoControls()),limit(options.optDouble("autoStrength",1),0,1.5)):new JSONObject();}

 /** Ajustes finais: preset completo com os básicos = automático + preset + delta manual. */
 static Develop.Settings settings(Develop.Source source,JSONObject options){
  JSONObject preset=options.optJSONObject("preset");Develop.Settings s=Develop.Settings.of(values(preset));
  JSONObject base=sumControls(autoValues(source,options),presetControls(preset));
  s.setBasic(array(sumControls(base,options.optJSONObject("manual"))));return s;
 }
 static int[] render(Develop.Source source,JSONObject options){return source.render(settings(source,options));}
 static Bitmap bitmap(Develop.Source source,JSONObject options){return Bitmap.createBitmap(render(source,options),source.w,source.h,Bitmap.Config.ARGB_8888);}
 static Develop.Source source(Bitmap b){int w=b.getWidth(),h=b.getHeight();int[] data=new int[w*h];b.getPixels(data,0,w,0,0,w,h);return new Develop.Source(data,w,h);}

 /** Aplica só o automático (testes e compatibilidade). */
 static void auto(int[] data){auto(data,1.0);}
 static void auto(int[] data,double strength){
  if(data.length==0)return;Develop.Source s=new Develop.Source(data.clone(),data.length,1);
  JSONObject o=new JSONObject();try{o.put("auto",true);o.put("autoStrength",strength);}catch(Exception ignored){}
  System.arraycopy(render(s,o),0,data,0,data.length);
 }

 // ---------------------------------------------------------------- Arquivos

 /** Decodifica o JPEG com o maior lado limitado a {@code edge} (0 = original) e orientação EXIF aplicada. */
 static Bitmap decode(File source,int edge)throws Exception{
  Bitmap bitmap=null;
  try{
   bitmap=decodeUnrotated(source,edge);
   int orientation=orientation(source);Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}
   if(orientation>1){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}
   if(!bitmap.isMutable()){Bitmap mutable=bitmap.copy(Bitmap.Config.ARGB_8888,true);bitmap.recycle();bitmap=mutable;if(bitmap==null)throw new IOException("Memória insuficiente.");}
   Bitmap result=bitmap;bitmap=null;return result;
  }finally{if(bitmap!=null)bitmap.recycle();}
 }
 static int orientation(File source){try{return new ExifInterface(source.toString()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(Exception e){return 1;}}
 /** JPEG decodificado com o maior lado limitado a {@code edge} (0 = original), na orientação do arquivo (sem girar). */
 static Bitmap decodeUnrotated(File source,int edge)throws Exception{
  Bitmap bitmap=null;
  try{
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.toString(),bounds);if(bounds.outWidth<=0)throw new IOException("JPEG inválido.");
   BitmapFactory.Options decode=new BitmapFactory.Options();decode.inMutable=true;decode.inPreferredConfig=Bitmap.Config.ARGB_8888;decode.inSampleSize=1;
   if(edge>0)while(Math.max(bounds.outWidth,bounds.outHeight)/(decode.inSampleSize*2)>=edge)decode.inSampleSize*=2;
   if((long)(bounds.outWidth/decode.inSampleSize)*(bounds.outHeight/decode.inSampleSize)>32000000)throw new IOException("Foto muito grande. Escolha uma resolução reduzida em Ajustes.");
   bitmap=BitmapFactory.decodeFile(source.toString(),decode);if(bitmap==null)throw new IOException("Não foi possível abrir JPEG.");
   if(edge>0&&Math.max(bitmap.getWidth(),bitmap.getHeight())>edge){double scale=edge/(double)Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(bitmap,(int)Math.round(bitmap.getWidth()*scale),(int)Math.round(bitmap.getHeight()*scale),true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}
   Bitmap result=bitmap;bitmap=null;return result;
  }finally{if(bitmap!=null)bitmap.recycle();}
 }
 /** Pixels já na orientação EXIF, copiados linha a linha (sem um segundo bitmap girado). size recebe largura e altura finais. */
 static int[] orientedPixels(Bitmap b,int orientation,int[] size){
  int w=b.getWidth(),h=b.getHeight();int[] out=new int[w*h];
  if(orientation<=1||orientation>8){b.getPixels(out,0,w,0,0,w,h);size[0]=w;size[1]=h;return out;}
  int[] row=new int[w];for(int y=0;y<h;y++){b.getPixels(row,0,w,0,y,w,1);Orientation.place(row,y,w,h,orientation,out);}
  boolean swap=Orientation.swaps(orientation);size[0]=swap?h:w;size[1]=swap?w:h;return out;
 }
 /** Maior lado da cópia editada escolhido em Ajustes (0 = resolução original, o padrão). */
 static int exportEdge(android.content.Context c){int res=Math.max(0,Math.min(2,c.getSharedPreferences("hisho",0).getInt("exportResolution",0)));return res==0?0:res==1?2560:1920;}
 /** Qualidade do JPEG exportado (mesma faixa do "Fine" das câmeras e do Lightroom em 95–100). */
 static final int JPEG_QUALITY=95;
 /** Metadados da câmera copiados para a foto editada; a orientação já foi aplicada nos pixels. */
 static final String[] EXIF_TAGS={ExifInterface.TAG_DATETIME,ExifInterface.TAG_DATETIME_ORIGINAL,ExifInterface.TAG_DATETIME_DIGITIZED,ExifInterface.TAG_OFFSET_TIME,ExifInterface.TAG_OFFSET_TIME_ORIGINAL,ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,ExifInterface.TAG_MAKE,ExifInterface.TAG_MODEL,"LensMake","LensModel","BodySerialNumber",ExifInterface.TAG_ARTIST,ExifInterface.TAG_COPYRIGHT,ExifInterface.TAG_EXPOSURE_TIME,ExifInterface.TAG_F_NUMBER,ExifInterface.TAG_ISO_SPEED_RATINGS,ExifInterface.TAG_FOCAL_LENGTH,ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,ExifInterface.TAG_EXPOSURE_BIAS_VALUE,ExifInterface.TAG_EXPOSURE_PROGRAM,ExifInterface.TAG_EXPOSURE_MODE,ExifInterface.TAG_METERING_MODE,ExifInterface.TAG_WHITE_BALANCE,ExifInterface.TAG_FLASH,ExifInterface.TAG_GPS_LATITUDE,ExifInterface.TAG_GPS_LATITUDE_REF,ExifInterface.TAG_GPS_LONGITUDE,ExifInterface.TAG_GPS_LONGITUDE_REF,ExifInterface.TAG_GPS_ALTITUDE,ExifInterface.TAG_GPS_ALTITUDE_REF,ExifInterface.TAG_GPS_TIMESTAMP,ExifInterface.TAG_GPS_DATESTAMP};
 static void copyExif(File source,File destination){
  try{ExifInterface in=new ExifInterface(source.toString()),out=new ExifInterface(destination.toString());boolean any=false;
   for(String tag:EXIF_TAGS){String v=in.getAttribute(tag);if(v!=null&&!v.isEmpty()){out.setAttribute(tag,v);any=true;}}
   if(!any)return;out.setAttribute(ExifInterface.TAG_ORIENTATION,String.valueOf(ExifInterface.ORIENTATION_NORMAL));out.saveAttributes();
  }catch(Exception ignored){}
 }
 /** Resultado da exportação: medições da curadoria (feitas enquanto o JPEG é gravado) e tempos de cada etapa. */
 static final class Export{SmartCurator.Partial curation;Exception curationError;String timing="";}
 static final java.util.concurrent.ExecutorService CURATION=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"lumo-curadoria");t.setDaemon(true);return t;});

 /**
  * Exporta a foto editada, por padrão em resolução original (edge 0):
  * decodifica → pixels já orientados (sem bitmap girado) → revela por cima dos próprios pixels (GPU, ou CPU de reserva) →
  * grava o JPEG enquanto a curadoria mede uma cópia de ~900 px em outra thread → copia o EXIF.
  * Só se a foto não couber na memória livre o lado é reduzido; se ainda faltar, tenta com 70% do lado.
  */
 static Export edit(File source,File destination,JSONObject options)throws Exception{
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.toString(),bounds);
  int longest=Math.max(bounds.outWidth,bounds.outHeight);Runtime rt=Runtime.getRuntime();
  int edge=Develop.fitEdge(bounds.outWidth,bounds.outHeight,options.optInt("edge",0),rt.maxMemory()-(rt.totalMemory()-rt.freeMemory()));
  final int orientation=orientation(source),sensitivity=Math.max(0,Math.min(2,options.optInt("curationSensitivity",1)));
  for(int attempt=0;;attempt++){
   Bitmap bitmap=null,face=null;java.util.concurrent.Future<SmartCurator.Partial> curation=null;
   try{
    long t0=android.os.SystemClock.elapsedRealtime();
    bitmap=decodeUnrotated(source,edge);int[] size=new int[2];int[] pixels=orientedPixels(bitmap,orientation,size);bitmap.recycle();bitmap=null;
    long t1=android.os.SystemClock.elapsedRealtime();
    Develop.Source s=new Develop.Source(pixels,size[0],size[1]);Develop.Settings developSettings=settings(s,options);
    StringBuilder why=new StringBuilder();boolean gpu=GpuDevelop.render(s,developSettings,why);
    if(!gpu)s.renderInPlace(developSettings);s=null;
    String engine=gpu?"GPU":"CPU"+(why.length()>0&&!"foto pequena".contentEquals(why)?" · "+why:"");
    long t2=android.os.SystemClock.elapsedRealtime();
    bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);bitmap.setPixels(pixels,0,size[0],0,0,size[0],size[1]);pixels=null;
    face=SmartCurator.shrink(bitmap,SmartCurator.FACE_EDGE);if(face==bitmap)face=bitmap.copy(Bitmap.Config.ARGB_8888,false);
    final Bitmap measured=face;curation=CURATION.submit(()->SmartCurator.measure(measured,sensitivity));
    // O arquivo é temporário (vai para a galeria em seguida): sem fsync.
    try(FileOutputStream output=new FileOutputStream(destination)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,JPEG_QUALITY,output))throw new IOException("Falha ao gerar JPEG.");}
    bitmap.recycle();bitmap=null;copyExif(source,destination);
    long t3=android.os.SystemClock.elapsedRealtime();
    Export result=new Export();
    try{result.curation=curation.get();}catch(java.util.concurrent.ExecutionException e){Throwable c=e.getCause();result.curationError=c instanceof Exception?(Exception)c:new Exception(String.valueOf(c));}
    long t4=android.os.SystemClock.elapsedRealtime();
    result.timing=String.format(Locale.ROOT,"abrir %.2f s · revelar %.2f s (%s) · JPEG %.2f s%s · %d×%d",(t1-t0)/1000.,(t2-t1)/1000.,engine,(t3-t2)/1000.,t4-t3>20?String.format(Locale.ROOT," · curadoria +%.2f s",(t4-t3)/1000.):"",size[0],size[1]);
    return result;
   }catch(GpuDevelop.Dirty e){
    // A GPU falhou depois de escrever parte dos pixels (e já foi desligada): abre a foto de novo e revela na CPU.
    attempt--;
   }catch(OutOfMemoryError e){
    if(bitmap!=null){bitmap.recycle();bitmap=null;}if(attempt>=2||longest<=0)throw e;
    edge=Math.max(1024,(int)((edge>0?Math.min(edge,longest):longest)*.7));System.gc();
   }finally{
    if(curation!=null)try{curation.get();}catch(Exception ignored){}
    if(face!=null)face.recycle();if(bitmap!=null)bitmap.recycle();
   }
  }
 }
}
