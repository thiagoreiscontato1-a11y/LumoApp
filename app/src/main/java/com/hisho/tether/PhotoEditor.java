package com.hisho.tether;
import android.graphics.*;import android.media.ExifInterface;import android.util.Xml;import org.xmlpull.v1.XmlPullParser;import org.json.*;import java.io.*;import java.util.*;
final class PhotoEditor {
 static final String[] COLORS={"Red","Orange","Yellow","Green","Aqua","Blue","Purple","Magenta"};
 static final Set<String> KEYS=new HashSet<>(Arrays.asList("IncrementalTemperature","IncrementalTint","Exposure2012","Contrast2012","Highlights2012","Shadows2012","Whites2012","Blacks2012","Clarity2012","Vibrance","Saturation","Sharpness","PostCropVignetteAmount"));
 static {for(String c:COLORS)for(String k:new String[]{"HueAdjustment","SaturationAdjustment","LuminanceAdjustment"})KEYS.add(k+c);}
 static JSONObject parse(String xml)throws Exception{
  if(xml.length()>1024*1024||xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))throw new IOException("XMP inválido ou maior que 1 MB.");
  XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);p.setInput(new StringReader(xml));JSONObject result=new JSONObject();String ns="http://ns.adobe.com/camera-raw-settings/1.0/";
  for(int event=p.getEventType();event!=XmlPullParser.END_DOCUMENT;event=p.next())if(event==XmlPullParser.START_TAG){
   for(int i=0;i<p.getAttributeCount();i++)if(ns.equals(p.getAttributeNamespace(i)))value(result,p.getAttributeName(i),p.getAttributeValue(i));
   if(ns.equals(p.getNamespace())&&KEYS.contains(p.getName())){String name=p.getName();value(result,name,p.nextText());}
  }
  if(result.length()==0)throw new IOException("Nenhum ajuste compatível encontrado no XMP.");return result;
 }
 static void value(JSONObject j,String key,String text)throws Exception{if(!KEYS.contains(key))return;double v=Double.parseDouble(text);double min=key.equals("Exposure2012")?-5:key.equals("Sharpness")?0:-100,max=key.equals("Exposure2012")?5:key.equals("Sharpness")?150:100;if(!Double.isFinite(v)||v<min||v>max)throw new IOException("Valor inválido: "+key);j.put(key,v);}
 static double clamp(double v){return Math.max(0,Math.min(1,v));}
 static int rgb(double r,double g,double b){return 0xff000000|((int)Math.round(clamp(r)*255)<<16)|((int)Math.round(clamp(g)*255)<<8)|(int)Math.round(clamp(b)*255);}
 static double p(JSONObject j,String k){return j.optDouble(k,0);}
 static void auto(int[] data){
  int[] hist=new int[256];int stride=Math.max(1,data.length/65536),count=0,neutral=0;double saturation=0,rs=0,gs=0,bs=0;
  for(int i=0;i<data.length;i+=stride){int v=data[i];double r=((v>>16)&255)/255.,g=((v>>8)&255)/255.,b=(v&255)/255.,l=.2126*r+.7152*g+.0722*b,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),sat=max==0?0:(max-min)/max;hist[(int)Math.round(l*255)]++;count++;saturation+=sat;if(l>.18&&l<.82&&sat<.18){neutral++;rs+=r;gs+=g;bs+=b;}}
  double lo=percentile(hist,count,.1),mid=percentile(hist,count,.5),hi=percentile(hist,count,.9),gamma=1;
  if(mid>.02&&mid<.38)gamma=Math.max(.82,Math.min(1,Math.log(.4)/Math.log(mid)));else if(mid>.65&&mid<.98)gamma=Math.max(1,Math.min(1.14,Math.log(.6)/Math.log(mid)));
  double contrast=hi-lo<.35&&hi-lo>.06?1.04:1,sat=saturation/count<.3?1.04:1;double[] gains={1,1,1};
  if(neutral>=Math.max(32,count*.03)){double avg=(rs+gs+bs)/3;double[] sums={rs,gs,bs};for(int i=0;i<3;i++)gains[i]=Math.max(.97,Math.min(1.03,1+.35*(avg/sums[i]-1)));}
  double[] lut=new double[256];for(int i=0;i<256;i++){double l=i/255.;lut[i]=(Math.pow(l,gamma)-l)*(gamma<1?1-l*l:1);}
  for(int i=0;i<data.length;i++){int v=data[i];double r=((v>>16)&255)/255.,g=((v>>8)&255)/255.,b=(v&255)/255.,l=(.2126*r+.7152*g+.0722*b)*255;int a=(int)l;double delta=lut[a]*(1-(l-a))+lut[Math.min(255,a+1)]*(l-a);r=clamp((clamp((r+delta)*gains[0])-.5)*contrast+.5);g=clamp((clamp((g+delta)*gains[1])-.5)*contrast+.5);b=clamp((clamp((b+delta)*gains[2])-.5)*contrast+.5);double y=.2126*r+.7152*g+.0722*b;data[i]=rgb(y+(r-y)*sat,y+(g-y)*sat,y+(b-y)*sat);}
 }
 static double percentile(int[] hist,int count,double fraction){int total=0;for(int i=0;i<256;i++){total+=hist[i];if(total>=count*fraction)return i/255.;}return 1;}
 static void preset(int[] data,int w,int h,JSONObject p){
  byte[] gray=new byte[data.length];int step=16,sw=(w+15)/16,sh=(h+15)/16;float[] small=new float[sw*sh],blur=new float[sw*sh];int[] counts=new int[sw*sh];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){int n=y*w+x,v=data[n];double g=.2126*((v>>16)&255)+.7152*((v>>8)&255)+.0722*(v&255);gray[n]=(byte)(int)g;int b=y/step*sw+x/step;small[b]+=g/255;counts[b]++;}
  for(int i=0;i<small.length;i++)small[i]/=counts[i];
  for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){float sum=0;int count=0;for(int yy=Math.max(0,y-2);yy<=Math.min(sh-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(sw-1,x+2);xx++){sum+=small[yy*sw+xx];count++;}blur[y*sw+x]=sum/count;}
  double[] centers={0,30,60,120,180,240,270,300,360},sats=new double[9],lums=new double[9],hues=new double[9];for(int i=0;i<9;i++){String c=COLORS[i%8];sats[i]=p(p,"SaturationAdjustment"+c)/100;lums[i]=p(p,"LuminanceAdjustment"+c)/100;hues[i]=p(p,"HueAdjustment"+c)*.3;}
  double temp=p(p,"IncrementalTemperature")/100,exposure=Math.pow(2,p(p,"Exposure2012")),tint=p(p,"IncrementalTint")/100,contrast=1+p(p,"Contrast2012")/100*.45,clarity=p(p,"Clarity2012")/100,sharp=p(p,"Sharpness")/100,saturation=p(p,"Saturation")/100,vibrance=p(p,"Vibrance")/100,vg=p(p,"PostCropVignetteAmount")/100;
  double[] tone=new double[256];for(int i=0;i<256;i++){double l=i/255.;tone[i]=p(p,"Shadows2012")/100*.22*Math.pow(1-l,2)+p(p,"Highlights2012")/100*.18*l*l+p(p,"Whites2012")/100*.12*Math.pow(l,4)+p(p,"Blacks2012")/100*.1*Math.pow(1-l,4);}
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int n=y*w+x,v=data[n],grayValue=gray[n]&255;double l=grayValue/255.,gx=x/16.,gy=y/16.;int bx=Math.min(sw-1,(int)gx),by=Math.min(sh-1,(int)gy);double fx=gx-bx,fy=gy-by,base=(blur[by*sw+bx]*(1-fx)+blur[by*sw+Math.min(sw-1,bx+1)]*fx)*(1-fy)+(blur[Math.min(sh-1,by+1)*sw+bx]*(1-fx)+blur[Math.min(sh-1,by+1)*sw+Math.min(sw-1,bx+1)]*fx)*fy;
   double near=((gray[y*w+Math.max(0,x-1)]&255)+(gray[y*w+Math.min(w-1,x+1)]&255)+(gray[Math.max(0,y-1)*w+x]&255)+(gray[Math.min(h-1,y+1)*w+x]&255))/1020.;double detail=(l-base)*clarity*.7*4*l*(1-l)+(l-near)*sharp*.55,delta=tone[grayValue];
   double r=clamp((clamp(((v>>16)&255)/255.*exposure*(1+temp*.18)+delta+detail)-.5)*contrast+.5),g=clamp((clamp(((v>>8)&255)/255.*exposure*(1-tint*.12)+delta+detail)-.5)*contrast+.5),b=clamp((clamp((v&255)/255.*exposure*(1-temp*.18)+delta+detail)-.5)*contrast+.5);
   double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),d=max-min,hue=0,sat=0; l=(max+min)/2;if(d>1e-7){sat=d/(1-Math.abs(2*l-1));hue=max==r?((g-b)/d)%6:max==g?(b-r)/d+2:(r-g)/d+4;hue=(hue*60+360)%360;}
   int band=0;while(band<7&&hue>=centers[band+1])band++;double mix=(hue-centers[band])/(centers[band+1]-centers[band]);sat=clamp(sat*(1+saturation)*(1+vibrance*(1-sat))*(1+sats[band]*(1-mix)+sats[band+1]*mix));l=clamp(l+(lums[band]*(1-mix)+lums[band+1]*mix)*.3*sat);hue=(hue+hues[band]*(1-mix)+hues[band+1]*mix+360)%360;
   double c=(1-Math.abs(2*l-1))*sat,hp=hue/60,z=c*(1-Math.abs(hp%2-1)),m=l-c/2,rr=0,gg=0,bb=0;
   if(hp<1){rr=c;gg=z;}else if(hp<2){rr=z;gg=c;}else if(hp<3){gg=c;bb=z;}else if(hp<4){gg=z;bb=c;}else if(hp<5){rr=z;bb=c;}else{rr=c;bb=z;}
   double radius=Math.pow((x+.5)/w*2-1,2)+Math.pow((y+.5)/h*2-1,2),edge=clamp((radius-.3)/1.7),vignette=1+vg*.85*edge*edge*(3-2*edge);data[n]=rgb((rr+m)*vignette,(gg+m)*vignette,(bb+m)*vignette);
  }
 }
 static void edit(File source,File destination,JSONObject options)throws Exception{
  Bitmap bitmap=null;try{
   int edge=options.optInt("edge",2560);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.toString(),bounds);if(bounds.outWidth<=0)throw new IOException("JPEG inválido.");
   BitmapFactory.Options decode=new BitmapFactory.Options();decode.inMutable=true;decode.inPreferredConfig=Bitmap.Config.ARGB_8888;decode.inSampleSize=1;
   if(edge>0)while(Math.max(bounds.outWidth,bounds.outHeight)/(decode.inSampleSize*2)>=edge)decode.inSampleSize*=2;
   if((long)(bounds.outWidth/decode.inSampleSize)*(bounds.outHeight/decode.inSampleSize)>32000000)throw new IOException("Foto muito grande. Use modo rápido.");
   bitmap=BitmapFactory.decodeFile(source.toString(),decode);if(bitmap==null)throw new IOException("Não foi possível abrir JPEG.");
   if(edge>0&&Math.max(bitmap.getWidth(),bitmap.getHeight())>edge){double scale=edge/(double)Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(bitmap,(int)Math.round(bitmap.getWidth()*scale),(int)Math.round(bitmap.getHeight()*scale),true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}
   int orientation=new ExifInterface(source.toString()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}
   if(orientation>1){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated;}
   if(!bitmap.isMutable()){Bitmap mutable=bitmap.copy(Bitmap.Config.ARGB_8888,true);bitmap.recycle();bitmap=mutable;if(bitmap==null)throw new IOException("Memória insuficiente.");}
   int w=bitmap.getWidth(),h=bitmap.getHeight();int[] data=new int[w*h];bitmap.getPixels(data,0,w,0,0,w,h);if(options.optBoolean("auto",true))auto(data);JSONObject preset=options.optJSONObject("preset");if(preset!=null)preset(data,w,h,preset);bitmap.setPixels(data,0,w,0,0,w,h);
   try(FileOutputStream output=new FileOutputStream(destination)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,output))throw new IOException("Falha ao gerar JPEG.");output.getFD().sync();}
  }finally{if(bitmap!=null)bitmap.recycle();}
 }
}
