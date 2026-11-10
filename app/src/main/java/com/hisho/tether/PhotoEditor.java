package com.hisho.tether;
import android.graphics.*;import android.media.ExifInterface;import android.util.Xml;import org.xmlpull.v1.XmlPullParser;import org.json.*;import java.io.*;import java.util.*;

/**
 * Edição de fotos do Lumo: decodifica o JPEG, resolve automático + preset XMP + ajuste manual
 * e renderiza tudo de uma vez no {@link Develop} (modelo do pipeline do Lightroom).
 * O ajuste manual é um delta somado aos controles básicos do preset/automático, como mover
 * os sliders do Lightroom depois de aplicar um preset. Os demais ajustes do editor (HSL, calibração,
 * efeitos, detalhe) ficam em {@code extra}: chaves Camera Raw com o valor final, que substituem as do preset
 * (mesmo formato de {@code EditOptions.extra} no iOS).
 */
final class PhotoEditor {
 static final String[] COLORS=Develop.COLORS;
 static final String[] CONTROLS=Develop.BASIC;

 // ---------------------------------------------------------------- Presets XMP

 static JSONObject parse(String xml)throws Exception{
  if(xml.length()>1024*1024||xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))throw new IOException("XMP inválido ou maior que 1 MB.");
  XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);p.setInput(new StringReader(xml));
  Map<String,String> look=new HashMap<>();
  JSONObject result=new JSONObject();for(Map.Entry<String,Double> e:Develop.parse(p,look).entrySet())result.put(e.getKey(),(double)e.getValue());
  JSONObject ref=lookReference(look);if(ref!=null)result.put("Look",ref);else result.remove("LookAmount");
  return result;
 }
 /**
  * Perfil criativo do XMP: guarda as tabelas (uma vez, pelo MD5) e devolve a referência que vai no preset,
  * ou null se o preset não tem perfil com tabela legível.
  */
 static JSONObject lookReference(Map<String,String> look)throws JSONException{
  String rgb=look.get("RGBTable"),lt=look.get("LookTable");boolean any=false;JSONObject ref=new JSONObject();
  for(String key:new String[]{rgb,lt}){if(key==null)continue;String text=look.get("Table_"+key);if(text==null)continue;try{CreativeProfile.store(key,text);any=true;}catch(IOException ignored){}}
  if(!any)return null;
  ref.put("name",look.containsKey("Name")?look.get("Name"):"Perfil criativo");
  if(rgb!=null&&CreativeProfile.load(rgb)!=null){ref.put("rgbTable",rgb.toUpperCase(Locale.ROOT));ref.put("rgbAmount",number(look.get("RGBTableAmount"),1));}
  if(lt!=null&&CreativeProfile.load(lt)!=null)ref.put("lookTable",lt.toUpperCase(Locale.ROOT));
  String g=look.get("ConvertToGrayscale");if(g!=null&&g.equalsIgnoreCase("true"))ref.put("grayscale",true);
  return ref;
 }
 static double number(String s,double def){if(s==null)return def;try{double v=Double.parseDouble(s.trim());return Double.isNaN(v)||Double.isInfinite(v)?def:v;}catch(NumberFormatException e){return def;}}
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

 /**
  * Preset com os ajustes extras do editor por cima (só chaves Camera Raw conhecidas). Uma curva de pontos no
  * extra ({@code ToneCurvePV2012.0}, {@code .1}…) substitui a curva inteira do preset.
  */
 static Map<String,Double> merged(JSONObject preset,JSONObject extra){return PresetMaker.merged(preset,extra);}
 /** Chave de ponto de curva ({@code ToneCurvePV2012Red.4}…). */
 static boolean curveKey(String k){return PresetMaker.curveKey(k);}

 // ---------------------------------------------------------------- Recorte e perfil criativo

 /** Recorte da foto ({@code options.crop}) ou null. */
 static Crop crop(JSONObject options){
  JSONObject c=options==null?null:options.optJSONObject("crop");if(c==null)return null;
  Crop k=new Crop(c.optDouble("angle",0),c.optDouble("left",0),c.optDouble("top",0),c.optDouble("right",1),c.optDouble("bottom",1));return k.isIdentity()?null:k;
 }
 static JSONObject cropJson(Crop c)throws JSONException{if(c==null||c.isIdentity())return null;JSONObject o=new JSONObject();o.put("angle",c.angle);o.put("left",c.left);o.put("top",c.top);o.put("right",c.right);o.put("bottom",c.bottom);return o;}
 /** Perfil criativo do preset, com a intensidade do editor ({@code LookAmount} no extra) por cima. */
 static CreativeProfile.Profile profile(JSONObject options){
  JSONObject preset=options==null?null:options.optJSONObject("preset");JSONObject look=preset==null?null:preset.optJSONObject("Look");if(look==null)return null;
  double amount=Develop.value("LookAmount",merged(preset,options.optJSONObject("extra")));
  return CreativeProfile.resolve(look.optString("rgbTable",null),look.optDouble("rgbAmount",1),look.optString("lookTable",null),amount,look.optBoolean("grayscale",false));
 }
 /** Pixels prontos para revelar: recortados/endireitados e com o perfil criativo. {@code size} entra e sai com largura e altura. */
 static int[] prepare(int[] px,int[] size,JSONObject options){
  Crop c=crop(options);if(c!=null){int[] o=new int[2];c=c.constrained(size[0],size[1]);px=c.apply(px,size[0],size[1],o);size[0]=o[0];size[1]=o[1];}
  CreativeProfile.Profile p=profile(options);if(p!=null)CreativeProfile.apply(px,size[0],size[1],p);
  return px;
 }
 /** Ajustes finais: preset (com os extras do editor) e os básicos = automático + preset + delta manual. */
 static Develop.Settings settings(Develop.Source source,JSONObject options){
  JSONObject preset=options.optJSONObject("preset");Develop.Settings s=Develop.Settings.of(merged(preset,options.optJSONObject("extra")));
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
 /** Correção de exposição só no automático: desligada com "Correção automática" desligada e em ajuste manual. */
 static boolean exposureFix(JSONObject options){JSONObject manual=options.optJSONObject("manual"),extra=options.optJSONObject("extra");return options.optBoolean("auto",true)&&(manual==null||manual.length()==0)&&(extra==null||extra.length()==0);}
 /** Rostos para a correção de exposição: {centro x, centro y, distância entre os olhos, confiança}. */
 static float[][] faces(int[] argb,int w,int h){
  int ew=w&~1;if(ew<32||h<32)return new float[0][];
  Bitmap b=Bitmap.createBitmap(argb,0,w,ew,h,Bitmap.Config.RGB_565);
  try{android.media.FaceDetector.Face[] found=new android.media.FaceDetector.Face[8];int n=new android.media.FaceDetector(ew,h,8).findFaces(b,found);
   float[][] out=new float[n][];for(int i=0;i<n;i++){PointF mid=new PointF();found[i].getMidPoint(mid);out[i]=new float[]{mid.x,mid.y,found[i].eyesDistance(),found[i].confidence()};}return out;
  }finally{b.recycle();}
 }
 /** Resultado da exportação: medições da curadoria (feitas enquanto o JPEG é gravado) e tempos de cada etapa. */
 static final class Export{SmartCurator.Partial curation;Exception curationError;PhotoCheck.Sharpness sharpness;PhotoCheck.Exposure exposure;String timing="";}
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
   Bitmap bitmap=null,face=null;java.util.concurrent.Future<SmartCurator.Partial> curation=null;java.util.concurrent.Future<PhotoCheck.Sharpness> sharp=null;
   try{
    long t0=android.os.SystemClock.elapsedRealtime();
    bitmap=decodeUnrotated(source,edge);int[] size=new int[2];int[] pixels=orientedPixels(bitmap,orientation,size);bitmap.recycle();bitmap=null;
    Crop crop=crop(options);if(crop!=null){int[] o=new int[2];pixels=crop.constrained(size[0],size[1]).apply(pixels,size[0],size[1],o);size[0]=o[0];size[1]=o[1];}
    long t1=android.os.SystemClock.elapsedRealtime();
    // Nitidez medida na ORIGINAL (~1600 px), em paralelo com a revelação: preset, nitidez e grão não enganam.
    int[] dsize=new int[2];final int[] detail=PhotoCheck.shrink(pixels,size[0],size[1],PhotoCheck.DETAIL_EDGE,dsize);final int dw=dsize[0],dh=dsize[1];
    sharp=CURATION.submit(()->PhotoCheck.sharpness(detail,dw,dh));
    CreativeProfile.Profile look=profile(options);if(look!=null)CreativeProfile.apply(pixels,size[0],size[1],look);
    Develop.Source s=new Develop.Source(pixels,size[0],size[1]);Develop.Settings developSettings=settings(s,options);
    // Exposição conferida na foto já com o preset (prévia de 512 px): clara ou escura demais é corrigida aqui.
    PhotoCheck.Exposure exposure=new PhotoCheck.Exposure();
    if(exposureFix(options)){int[] psize=new int[2];int[] small=PhotoCheck.shrink(detail,dw,dh,PhotoCheck.PREVIEW_EDGE,psize);exposure=PhotoCheck.fixExposure(small,psize[0],psize[1],developSettings,PhotoEditor::faces);}
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
    Export result=new Export();result.exposure=exposure;
    try{result.curation=curation.get();}catch(java.util.concurrent.ExecutionException e){Throwable c=e.getCause();result.curationError=c instanceof Exception?(Exception)c:new Exception(String.valueOf(c));}
    try{result.sharpness=sharp.get();}catch(java.util.concurrent.ExecutionException ignored){}
    long t4=android.os.SystemClock.elapsedRealtime();
    result.timing=String.format(Locale.ROOT,"abrir %.2f s · revelar %.2f s (%s) · JPEG %.2f s%s%s · %d×%d",(t1-t0)/1000.,(t2-t1)/1000.,engine,(t3-t2)/1000.,t4-t3>20?String.format(Locale.ROOT," · curadoria +%.2f s",(t4-t3)/1000.):"",exposure.ev!=0?String.format(Locale.ROOT," · exposição %+.2f EV",exposure.ev):"",size[0],size[1]);
    return result;
   }catch(GpuDevelop.Dirty e){
    // A GPU falhou depois de escrever parte dos pixels (e já foi desligada): abre a foto de novo e revela na CPU.
    attempt--;
   }catch(OutOfMemoryError e){
    if(bitmap!=null){bitmap.recycle();bitmap=null;}if(attempt>=2||longest<=0)throw e;
    edge=Math.max(1024,(int)((edge>0?Math.min(edge,longest):longest)*.7));System.gc();
   }finally{
    if(curation!=null)try{curation.get();}catch(Exception ignored){}
    if(sharp!=null)try{sharp.get();}catch(Exception ignored){}
    if(face!=null)face.recycle();if(bitmap!=null)bitmap.recycle();
   }
  }
 }
}
