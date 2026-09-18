package com.hisho.tether;

import android.content.*;import android.database.Cursor;import android.net.Uri;import android.provider.OpenableColumns;import org.json.*;import java.io.*;import java.net.*;import java.util.*;

final class FottoApi {
 static final String BASE="https://api.fotto.com.br/api";
 static final class Gallery {final String id,title;Gallery(String id,String title){this.id=id;this.title=title;}public String toString(){return title;}}
 static final class Uploaded {final String mediaId;final boolean skipped;Uploaded(String mediaId){this(mediaId,false);}Uploaded(String mediaId,boolean skipped){this.mediaId=mediaId;this.skipped=skipped;}}
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("hisho",0);}
 static String accessToken(Context c){String v=prefs(c).getString("fottoAccessTokenV7","").trim();if(!v.isEmpty())return stripBearer(v);v=prefs(c).getString("fottoAccessTokenV5","").trim();if(!v.isEmpty())return stripBearer(v);v=prefs(c).getString("fottoAccessTokenV4","").trim();if(!v.isEmpty())return stripBearer(v);v=prefs(c).getString("fottoTokenV3","").trim();return stripBearer(v);}
 static String apiKey(Context c){String v=prefs(c).getString("fottoApiKeyV7","").trim();if(!v.isEmpty())return stripBearer(v);v=prefs(c).getString("fottoApiKeyV5","").trim();if(!v.isEmpty())return stripBearer(v);return stripBearer(prefs(c).getString("fottoApiKeyV4","").trim());}
 static String token(Context c){String k=apiKey(c);return !k.isEmpty()?k:accessToken(c);}
 static HttpURLConnection connection(String url,String method,String token)throws IOException{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setUseCaches(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Lumo/0.13.0 Android");
  if(token!=null&&!token.isEmpty()){c.setRequestProperty("App-Code","fotto");c.setRequestProperty("Authorization",stripBearer(token));}
  return c;
 }
 static HttpURLConnection connectionBearer(String url,String method,String token)throws IOException{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setUseCaches(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Lumo/0.13.0 Android");
  if(token!=null&&!token.isEmpty()){c.setRequestProperty("App-Code","fotto");c.setRequestProperty("Authorization","Bearer "+stripBearer(token));}
  return c;
 }
 static String stripBearer(String s){s=s==null?"":s.trim();return s.regionMatches(true,0,"Bearer ",0,7)?s.substring(7).trim():s;}
 static String call(Context ctx,String method,String path,String body)throws IOException{
  String tok=token(ctx);if(tok.isEmpty())throw new IOException("Conecte sua conta Fotto novamente.");return callWithToken(method,path,body,tok);
 }
 static String callWithToken(String method,String path,String body,String tok)throws IOException{return callWithTokenMode(method,path,body,tok,false);}
 static String callWithBearerToken(String method,String path,String body,String tok)throws IOException{return callWithTokenMode(method,path,body,tok,true);}
 static String callWithTokenMode(String method,String path,String body,String tok,boolean bearer)throws IOException{
  HttpURLConnection c=bearer?connectionBearer(BASE+path,method,tok):connection(BASE+path,method,tok);
  if(body!=null){byte[] bytes=body.getBytes("UTF-8");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
  int code=c.getResponseCode();String text=read(code>=200&&code<300?c.getInputStream():c.getErrorStream(),1024*1024);c.disconnect();
  if(code<200||code>=300){if(code==401)throw new IOException("Sessão Fotto expirada. Toque em Conectar com Fotto novamente.");throw new IOException("Fotto HTTP "+code+" em "+path+(text.isEmpty()?"":" · "+compact(text)));}return text;
 }
 static String validateSession(Context c)throws Exception{return call(c,"GET","/me",null);}
 static ArrayList<Gallery> galleries(Context c)throws Exception{
  validateSession(c);String json=call(c,"GET","/me/galleries",null);Object root=new JSONTokener(json).nextValue();JSONArray arr=findArray(root,"galleries");if(arr==null){Object d=root instanceof JSONObject?((JSONObject)root).opt("data"):null;if(d instanceof JSONArray)arr=(JSONArray)d;else if(d!=null)arr=findArray(d,"galleries");}
  ArrayList<Gallery> list=new ArrayList<>();if(arr==null)return list;for(int i=0;i<arr.length();i++){JSONObject g=arr.optJSONObject(i);if(g==null)continue;String id=value(g.opt("id")),title=g.optString("title",g.optString("name","Evento "+id));if(!id.isEmpty())list.add(new Gallery(id,title));}return list;
 }
 static Uploaded upload(Context ctx,Uri source,String name,String galleryId)throws Exception{
  long size=size(ctx,source);if(size<0)size=count(ctx,source);if(size<=0)throw new IOException("CREATE_MEDIA_ARRAY · arquivo vazio: "+name);

  /*
   * O app de referência do Fotto envia diretamente uma LISTA JSON para
   * POST /me/galleries/{id}/medias.
   *
   * Formato real:
   * [
   *   {
   *     "originalFileName":"IMG_0001.jpg",
   *     "mediaType":"photo",
   *     "mediaSize":123456
   *   }
   * ]
   *
   * Não existe wrapper {"gallery_id":...,"medias":[...]} nesse endpoint.
   */
  JSONObject item=new JSONObject();
  item.put("originalFileName",name);
  item.put("mediaType","photo");
  item.put("mediaSize",size);
  JSONArray body=new JSONArray();
  body.put(item);

  String path="/me/galleries/"+Uri.encode(galleryId)+"/medias";
  String auth=apiKey(ctx);if(auth.isEmpty())auth=accessToken(ctx);
  if(auth.isEmpty())throw new IOException("CREATE_MEDIA_ARRAY · sessão Fotto ausente. Reconecte a conta.");

  String response;
  try{
   // O cliente do app de referência envia o accessToken/apiKey diretamente
   // no cabeçalho Authorization, sem acrescentar "Bearer ".
   response=callWithToken("POST",path,body.toString(),auth);
  }catch(IOException e){
   throw new IOException("CREATE_MEDIA_ARRAY · "+name+" · "+size+" bytes · "+e.getMessage(),e);
  }

  Object root=new JSONTokener(response).nextValue();
  JSONObject media=findObject(root,"signedUrl");
  if(media==null)throw new IOException("CREATE_MEDIA_ARRAY · o Fotto não retornou signedUrl. Resposta: "+compact(response));
  String signed=media.optString("signedUrl","");
  if(signed.isEmpty())throw new IOException("CREATE_MEDIA_ARRAY · URL assinada vazia.");
  String mediaId=value(media.opt("id"));
  put(ctx,source,name,size,signed);
  return new Uploaded(mediaId);
 }
 static String confirmMedia(Context ctx,String galleryId,String expectedId,String name)throws Exception{
  String json=call(ctx,"GET","/me/galleries/"+Uri.encode(galleryId)+"/medias",null);Object root=new JSONTokener(json).nextValue();JSONArray arr=findArray(root,"medias");
  if(arr==null&&root instanceof JSONObject){Object d=((JSONObject)root).opt("data");if(d instanceof JSONArray)arr=(JSONArray)d;else if(d!=null)arr=findArray(d,"medias");}
  if(arr==null)return "";
  for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;String id=value(m.opt("id")),original=m.optString("originalFileName",m.optString("name",""));
   if(expectedId!=null&&!expectedId.isEmpty()&&expectedId.equals(id))return id;
   if(name!=null&&!name.isEmpty()&&name.equalsIgnoreCase(original))return id;
  }
  return "";
 }
 static String findExistingMedia(Context ctx,String galleryId,String name){
  try{String json=call(ctx,"GET","/me/galleries/"+Uri.encode(galleryId)+"/medias",null);Object root=new JSONTokener(json).nextValue();JSONArray arr=findArray(root,"medias");if(arr==null&&root instanceof JSONObject){Object d=((JSONObject)root).opt("data");if(d instanceof JSONArray)arr=(JSONArray)d;else if(d!=null)arr=findArray(d,"medias");}if(arr==null)return "";for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;String original=m.optString("originalFileName",m.optString("name",""));if(name.equalsIgnoreCase(original))return value(m.opt("id"));}}catch(Exception ignored){}return "";
 }
 static void put(Context ctx,Uri source,String name,long size,String url)throws IOException{
  HttpURLConnection c=connection(url,"PUT",null);c.setDoOutput(true);c.setRequestProperty("Content-Type","image/jpeg");c.setRequestProperty("Content-Disposition","attachment; filename*=UTF-8''"+Uri.encode(name));if(size>0)c.setFixedLengthStreamingMode(size);else c.setChunkedStreamingMode(128*1024);
  try(InputStream in=ctx.getContentResolver().openInputStream(source);OutputStream out=c.getOutputStream()){if(in==null)throw new IOException("Arquivo editado não está mais disponível.");copy(in,out);}int code=c.getResponseCode();String err=read(code>=200&&code<300?c.getInputStream():c.getErrorStream(),65536);c.disconnect();if(code<200||code>=300)throw new IOException("UPLOAD_BINÁRIO HTTP "+code+(err.isEmpty()?"":" · "+compact(err)));
 }
 static long size(Context c,Uri u){try(Cursor q=c.getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null)){if(q!=null&&q.moveToFirst()&&!q.isNull(0))return q.getLong(0);}catch(Exception ignored){}return -1;}
 static long count(Context c,Uri u)throws IOException{long n=0;byte[] b=new byte[65536];try(InputStream in=c.getContentResolver().openInputStream(u)){if(in==null)throw new IOException("Arquivo editado indisponível.");for(int r;(r=in.read(b))!=-1;)n+=r;}return n;}
 static JSONArray findArray(Object v,String key){if(v instanceof JSONObject){JSONObject o=(JSONObject)v;Object direct=o.opt(key);if(direct instanceof JSONArray)return (JSONArray)direct;Iterator<String> it=o.keys();while(it.hasNext()){Object child=o.opt(it.next());JSONArray a=findArray(child,key);if(a!=null)return a;}}else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){JSONArray x=findArray(a.opt(i),key);if(x!=null)return x;}}return null;}
 static JSONObject findObject(Object v,String key){if(v instanceof JSONObject){JSONObject o=(JSONObject)v;if(o.has(key))return o;Iterator<String> it=o.keys();while(it.hasNext()){JSONObject x=findObject(o.opt(it.next()),key);if(x!=null)return x;}}else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){JSONObject x=findObject(a.opt(i),key);if(x!=null)return x;}}return null;}
 static String value(Object v){return v==null||v==JSONObject.NULL?"":String.valueOf(v);}
 static String read(InputStream in,int max)throws IOException{if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=x.read(b))!=-1;){if(out.size()+n>max){out.write(b,0,Math.max(0,max-out.size()));break;}out.write(b,0,n);}return out.toString("UTF-8");}}
 static String compact(String s){s=s.replaceAll("\\s+"," ").trim();return s.length()>350?s.substring(0,350)+"…":s;}
 static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[128*1024];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
}
