package com.hisho.tether;

import android.content.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;

/**
 * Cliente da galeria online do Lumo (Cloudflare Worker em online-gallery/worker).
 * Cada foto vai em três variantes: miniatura (640 px), tela cheia (2048 px) e o JPEG editado completo.
 * O servidor confere tamanho e SHA-256 de cada arquivo e só publica depois do "complete".
 */
final class GalleryApi {
 static final int THUMB_EDGE=640,PREVIEW_EDGE=2048;

 static final class Gallery {
  final String id,slug,title,url;final boolean hasPin;
  Gallery(JSONObject o){id=o.optString("id");slug=o.optString("slug");title=o.optString("title");url=o.optString("url");hasPin=o.optBoolean("hasPin");}
  Gallery(String id,String slug,String title,String url,boolean hasPin){this.id=id;this.slug=slug;this.title=title;this.url=url;this.hasPin=hasPin;}
  JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("slug",slug).put("title",title).put("url",url).put("hasPin",hasPin);}
 }

 static SharedPreferences prefs(Context c){return c.getSharedPreferences("hisho",0);}
 static String server(Context c){return normalizeServer(prefs(c).getString("lgServer",""));}
 static String token(Context c){return prefs(c).getString("lgToken","").trim();}
 static boolean configured(Context c){return !server(c).isEmpty()&&!token(c).isEmpty();}

 /** Aceita "lumo-galeria.conta.workers.dev", com ou sem https:// e barra final. */
 static String normalizeServer(String value){
  String s=value==null?"":value.trim();if(s.isEmpty())return "";
  if(!s.matches("(?i)^https?://.*"))s="https://"+s;
  while(s.endsWith("/"))s=s.substring(0,s.length()-1);
  int admin=s.indexOf("/admin");if(admin>0)s=s.substring(0,admin);
  return s;
 }

 /** Galeria ligada ao evento local ativo (cada evento do Lumo tem a sua). */
 static Gallery galleryFor(Context c,String eventId){
  String raw=prefs(c).getString("lgGallery_"+eventId,"");if(raw.isEmpty())return null;
  try{JSONObject o=new JSONObject(raw);return new Gallery(o.getString("id"),o.optString("slug"),o.optString("title"),o.optString("url"),o.optBoolean("hasPin"));}catch(Exception e){return null;}
 }
 static void saveGallery(Context c,String eventId,Gallery g){try{prefs(c).edit().putString("lgGallery_"+eventId,g.json().toString()).apply();}catch(JSONException ignored){}}
 static void forgetGallery(Context c,String eventId){prefs(c).edit().remove("lgGallery_"+eventId).apply();}

 static JSONObject call(String server,String token,String method,String path,JSONObject body)throws IOException{
  HttpURLConnection c=null;
  try{
   c=(HttpURLConnection)new URL(server+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setUseCaches(false);
   c.setRequestProperty("Accept","application/json");c.setRequestProperty("Authorization","Bearer "+token);
   if(body!=null){byte[] bytes=body.toString().getBytes("UTF-8");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
   return response(c);
  }finally{if(c!=null)c.disconnect();}
 }

 static JSONObject call(Context ctx,String method,String path,JSONObject body)throws IOException{
  if(!configured(ctx))throw new IOException("Conecte a galeria online primeiro.");
  return call(server(ctx),token(ctx),method,path,body);
 }

 private static JSONObject response(HttpURLConnection c)throws IOException{
  int code=c.getResponseCode();String text=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());
  JSONObject o;try{o=text.isEmpty()?new JSONObject():new JSONObject(text);}catch(JSONException e){o=new JSONObject();}
  if(code>=200&&code<300)return o;
  String msg=o.optString("error","");
  if(code==401)throw new IOException("Chave da galeria recusada. Confira a chave em Galeria online.");
  if(code==404&&msg.isEmpty())throw new IOException("Servidor da galeria não encontrado. Confira o endereço.");
  throw new IOException(msg.isEmpty()?"Galeria online respondeu "+code:msg);
 }

 private static String read(InputStream in)throws IOException{
  if(in==null)return "";
  try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))>0&&out.size()<1024*1024)out.write(b,0,n);return out.toString("UTF-8");}
 }

 /** Confere endereço e chave; devolve o nome do estúdio configurado no servidor. */
 static String check(String server,String token)throws IOException{
  try{return call(server,token,"GET","/v1/me",null).optString("studio","Lumo");}
  catch(UnknownHostException e){throw new IOException("Endereço não encontrado: "+server);}
 }

 static Gallery createGallery(Context c,String title,String clientKey,String pin,boolean allowDownload)throws IOException{
  try{
   JSONObject body=new JSONObject().put("title",title).put("clientKey",clientKey).put("allowDownload",allowDownload);
   if(pin!=null&&!pin.trim().isEmpty())body.put("pin",pin.trim());
   return new Gallery(call(c,"POST","/v1/galleries",body).getJSONObject("gallery"));
  }catch(JSONException e){throw new IOException("Resposta inválida da galeria online.");}
 }

 static JSONObject galleryInfo(Context c,String id)throws IOException{
  try{return call(c,"GET","/v1/galleries/"+id,null).getJSONObject("gallery");}catch(JSONException e){throw new IOException("Resposta inválida da galeria online.");}
 }

 /** Publica uma foto editada. Seguro para repetir: o servidor reconhece variantes já recebidas. Devolve o ID remoto. */
 static String upload(Context ctx,String galleryId,String clientPhotoId,long revision,Uri edited,String name,long takenAt)throws IOException{
  byte[] full=readAll(ctx,edited);
  int orientation=ExifInterface.ORIENTATION_NORMAL;
  try{orientation=new ExifInterface(new ByteArrayInputStream(full)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(Exception ignored){}
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(full,0,full.length,bounds);
  if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Foto editada ilegível: "+name);
  boolean turned=orientation==ExifInterface.ORIENTATION_ROTATE_90||orientation==ExifInterface.ORIENTATION_ROTATE_270;
  int width=turned?bounds.outHeight:bounds.outWidth,height=turned?bounds.outWidth:bounds.outHeight;
  byte[] preview=scaled(full,bounds,PREVIEW_EDGE,86,orientation),thumb=scaled(full,bounds,THUMB_EDGE,80,orientation);
  return publish(server(ctx),token(ctx),galleryId,clientPhotoId,revision,name,takenAt,width,height,thumb,preview,full);
 }

 /** Parte de rede do envio, sem dependência do Android (testada na JVM contra o servidor local). */
 static String publish(String server,String token,String galleryId,String clientPhotoId,long revision,String name,long takenAt,int width,int height,byte[] thumb,byte[] preview,byte[] full)throws IOException{
  byte[][] files={thumb,preview,full};String[] names={"thumb","preview","full"};
  try{
   JSONObject variants=new JSONObject();for(int i=0;i<3;i++)variants.put(names[i],new JSONObject().put("size",files[i].length).put("sha256",sha256(files[i])));
   JSONObject body=new JSONObject().put("galleryId",galleryId).put("clientPhotoId",clientPhotoId).put("revision",revision).put("filename",name)
    .put("takenAt",takenAt>0?takenAt:System.currentTimeMillis()).put("width",width).put("height",height).put("variants",variants);
   JSONObject plan=call(server,token,"POST","/v1/photos",body);String photoId=plan.getString("photoId");
   if(plan.optBoolean("ready"))return photoId;
   JSONArray uploads=plan.getJSONArray("uploads");
   for(int i=0;i<uploads.length();i++){
    JSONObject up=uploads.getJSONObject(i);if(up.optBoolean("done"))continue;
    String variant=up.getString("variant");byte[] data=variant.equals("thumb")?thumb:variant.equals("preview")?preview:full;
    put(server,token,up.getString("url"),data);
   }
   call(server,token,"POST",plan.getString("completeUrl"),new JSONObject().put("revision",revision));
   return photoId;
  }catch(JSONException e){throw new IOException("Resposta inválida da galeria online.");}
 }

 private static void put(String server,String token,String path,byte[] data)throws IOException{
  HttpURLConnection c=null;
  try{
   c=(HttpURLConnection)new URL(server+path).openConnection();c.setRequestMethod("PUT");c.setConnectTimeout(15000);c.setReadTimeout(120000);c.setUseCaches(false);
   c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","image/jpeg");c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);
   try(OutputStream out=c.getOutputStream()){out.write(data);}
   response(c);
  }finally{if(c!=null)c.disconnect();}
 }

 private static byte[] readAll(Context c,Uri uri)throws IOException{
  try(InputStream in=c.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   if(in==null)throw new FileNotFoundException("Foto editada indisponível.");
   byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();
  }
 }

 /** Reduz para o lado maior = edge (sem ampliar), aplica a rotação do EXIF e grava JPEG. */
 static byte[] scaled(byte[] jpeg,BitmapFactory.Options bounds,int edge,int quality,int orientation)throws IOException{
  int longest=Math.max(bounds.outWidth,bounds.outHeight),sample=1;while(longest/(sample*2)>=edge)sample*=2;
  BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=sample;
  Bitmap b=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,o);if(b==null)throw new IOException("Não foi possível reduzir a foto.");
  try{
   float scale=Math.min(1f,edge/(float)Math.max(b.getWidth(),b.getHeight()));
   Matrix m=new Matrix();m.postScale(scale,scale);
   if(orientation==ExifInterface.ORIENTATION_ROTATE_90)m.postRotate(90);else if(orientation==ExifInterface.ORIENTATION_ROTATE_180)m.postRotate(180);else if(orientation==ExifInterface.ORIENTATION_ROTATE_270)m.postRotate(270);
   Bitmap out=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);
   try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){out.compress(Bitmap.CompressFormat.JPEG,quality,bytes);return bytes.toByteArray();}
   finally{if(out!=b)out.recycle();}
  }finally{b.recycle();}
 }

 static String sha256(byte[] data){
  try{byte[] d=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format(java.util.Locale.ROOT,"%02x",x&0xff));return s.toString();}
  catch(Exception e){throw new IllegalStateException(e);}
 }

 private GalleryApi(){}
}
