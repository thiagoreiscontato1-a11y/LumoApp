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
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.toString(),bounds);if(bounds.outWidth<=0)throw new IOException("JPEG inválido.");
   BitmapFactory.Options decode=new BitmapFactory.Options();decode.inMutable=true;decode.inPreferredConfig=Bitmap.Config.ARGB_8888;decode.inSampleSize=1;
   if(edge>0)while(Math.max(bounds.outWidth,bounds.outHeight)/(decode.inSampleSize*2)>=edge)decode.inSampleSize*=2;
   if((long)(bounds.outWidth/decode.inSampleSize)*(bounds.outHeight/decode.inSampleSize)>32000000)throw new IOException("Foto muito grande. Use modo rápido.");
   bitmap=BitmapFactory.decodeFile(source.toString(),decode);if(bitmap==null)throw new IOException("Não foi possível abrir JPEG.");
   if(edge>0&&Math.max(bitmap.getWidth(),bitmap.getHeight())>edge){double scale=edge/(double)Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(bitmap,(int)Math.round(bitmap.getWidth()*scale),(int)Math.round(bitmap.getHeight()*scale),true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}
   int orientation=new ExifInterface(source.toString()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}
   if(orientation>1){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}
   if(!bitmap.isMutable()){Bitmap mutable=bitmap.copy(Bitmap.Config.ARGB_8888,true);bitmap.recycle();bitmap=mutable;if(bitmap==null)throw new IOException("Memória insuficiente.");}
   Bitmap result=bitmap;bitmap=null;return result;
  }finally{if(bitmap!=null)bitmap.recycle();}
 }
 static void edit(File source,File destination,JSONObject options)throws Exception{
  Bitmap bitmap=null;try{
   bitmap=decode(source,options.optInt("edge",2560));Develop.Source s=source(bitmap);
   bitmap.setPixels(render(s,options),0,s.w,0,0,s.w,s.h);
   try(FileOutputStream output=new FileOutputStream(destination)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,output))throw new IOException("Falha ao gerar JPEG.");output.getFD().sync();}
  }finally{if(bitmap!=null)bitmap.recycle();}
 }
}
