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
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setUseCaches(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Lumo/0.15.21 Android");
  if(token!=null&&!token.isEmpty()){c.setRequestProperty("App-Code","fotto");c.setRequestProperty("Authorization",stripBearer(token));}
  return c;
 }
 static HttpURLConnection connectionBearer(String url,String method,String token)throws IOException{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setUseCaches(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","Lumo/0.15.21 Android");
  if(token!=null&&!token.isEmpty()){c.setRequestProperty("App-Code","fotto");c.setRequestProperty("Authorization","Bearer "+stripBearer(token));}
  return c;
 }
 static String stripBearer(String s){s=s==null?"":s.trim();return s.regionMatches(true,0,"Bearer ",0,7)?s.substring(7).trim():s;}
 static String call(Context ctx,String method,String path,String body)throws IOException{
  String api=apiKey(ctx);if(!api.isEmpty())return callWithToken(method,path,body,api);
  String access=accessToken(ctx);if(!access.isEmpty())return callWithBearerToken(method,path,body,access);
  throw new IOException("Conecte sua conta Fotto novamente.");
 }
 static String callWithToken(String method,String path,String body,String tok)throws IOException{return callWithTokenMode(method,path,body,tok,false);}
 static String callWithBearerToken(String method,String path,String body,String tok)throws IOException{return callWithTokenMode(method,path,body,tok,true);}
 static String callWithTokenMode(String method,String path,String body,String tok,boolean bearer)throws IOException{
  IOException last=null;
  for(int attempt=0;attempt<4;attempt++){
   HttpURLConnection c=null;
   try{
    c=bearer?connectionBearer(BASE+path,method,tok):connection(BASE+path,method,tok);
    if(body!=null){byte[] bytes=body.getBytes("UTF-8");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
    int code=c.getResponseCode();String text=read(code>=200&&code<300?c.getInputStream():c.getErrorStream(),4*1024*1024);
    if(code>=200&&code<300)return text;
    if(code==401)throw new IOException("Sessão Fotto expirada. Toque em Conectar com Fotto novamente.");
    IOException error=new IOException("Fotto HTTP "+code+" em "+path+(text.isEmpty()?"":" · "+compact(text)));
    if((code==429||code==502||code==503||code==504)&&attempt<3){last=error;long wait=900L*(attempt+1);String retry=c.getHeaderField("Retry-After");try{if(retry!=null&&!retry.trim().isEmpty())wait=Math.max(wait,Long.parseLong(retry.trim())*1000L);}catch(Exception ignored){}try{Thread.sleep(Math.min(wait,6000L));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw error;}continue;}
    throw error;
   }catch(IOException e){last=e;if(attempt>=3||e.getMessage()!=null&&e.getMessage().contains("Sessão Fotto expirada"))throw e;if(attempt<3){try{Thread.sleep(700L*(attempt+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw e;}}}
   finally{if(c!=null)c.disconnect();}
  }
  throw last==null?new IOException("Falha de comunicação com o Fotto."):last;
 }
 static String validateSession(Context c)throws Exception{return call(c,"GET","/me",null);}
 /** Snapshot of the account galleries, including whether the server gave a verifiable end-of-list. */
 static final class GalleryListing {
  final ArrayList<Gallery> items;
  final boolean complete;
  final String warning;
  GalleryListing(ArrayList<Gallery> items,boolean complete,String warning){this.items=items;this.complete=complete;this.warning=warning;}
 }
 static ArrayList<Gallery> galleries(Context c)throws Exception{return galleriesDetailed(c).items;}
 static GalleryListing galleriesDetailed(Context c)throws Exception{
  validateSession(c);
  // The former implementation fetched only /me/galleries once. If Fotto paginates
  // the result, that silently hid older galleries.
  LinkedHashMap<String,Gallery> unique=new LinkedHashMap<>();
  String path="/me/galleries";
  Set<String> requested=new HashSet<>();
  boolean triedPageOne=false,implicitPaging=false,complete=false;
  String warning="";
  int inferredPage=1;
  for(int request=0;request<300;request++){
   if(!requested.add(path)){warning="Paginação repetida pelo Fotto.";break;}
   Object root;
   try{root=new JSONTokener(call(c,"GET",path,null)).nextValue();}
   catch(Exception err){
    if(unique.isEmpty())throw err;
    warning="A busca foi interrompida na página "+inferredPage+": "+err.getMessage();break;
   }
   JSONArray rows=galleryArray(root);
   if(rows==null){warning="A API do Fotto não retornou a lista esperada de eventos.";break;}
   int added=0;
   for(int i=0;i<rows.length();i++){
    JSONObject g=rows.optJSONObject(i);if(g==null)continue;
    String id=value(g.opt("id"));if(id.isEmpty())continue;
    String title=g.optString("title",g.optString("name","Evento "+id));
    if(title.isEmpty())title="Evento "+id;
    if(!unique.containsKey(id)){unique.put(id,new Gallery(id,title));added++;}
   }
   if(rows.length()==0){complete=true;break;}
   if(unique.size()>=20000){warning="Mais de 20 mil eventos: limite de proteção atingido.";break;}
   String next=nextGalleryPath(root);
   if(next!=null){
    if(added==0&&request>0){warning="O Fotto repetiu uma página de eventos.";break;}
    path=next;implicitPaging=false;inferredPage++;continue;
   }
   if(paginationEnded(root)){complete=true;break;}
   if(hasPaginationInfo(root)){
    // A count or current-page marker can exist without a next URL.
    Integer current=numberFrom(root,"page","currentPage","current_page");
    Integer totalPages=numberFrom(root,"totalPages","lastPage","total_pages","last_page","pages");
    if(current!=null&&totalPages!=null&&current>=totalPages){complete=true;break;}
    if(added==0&&request>0){warning="O Fotto repetiu a página "+inferredPage+".";break;}
    inferredPage=current==null?inferredPage+1:current+1;
    path="/me/galleries?page="+inferredPage;continue;
   }
   // Undocumented pagination: probe page=1 once. It can be the same as the
   // unparameterized first page (1-based API), or the next page (0-based API).
   if(!triedPageOne){
    triedPageOne=true;implicitPaging=true;path="/me/galleries?page=1";continue;
   }
   if(added==0){
    if(implicitPaging&&inferredPage==1){
     // page=1 == default page, so try page=2 before deciding pagination is absent.
     inferredPage=2;path="/me/galleries?page=2";continue;
    }
    // API ignored the page parameter or we reached a repeated final page.
    complete=false;
    warning="O Fotto não confirmou a paginação; alguns eventos antigos podem não vir pela API.";
    break;
   }
   inferredPage=implicitPaging&&inferredPage==1?2:inferredPage+1;
   implicitPaging=false;
   path="/me/galleries?page="+inferredPage;
  }
  if(requested.size()>=300&&!complete)warning="Limite de 300 páginas atingido.";
  return new GalleryListing(new ArrayList<>(unique.values()),complete,warning);
 }
 static JSONArray galleryArray(Object root){
  if(root instanceof JSONArray)return (JSONArray)root;
  if(!(root instanceof JSONObject))return null;
  JSONObject obj=(JSONObject)root;
  JSONArray arr=obj.optJSONArray("galleries");if(arr!=null)return arr;
  Object data=obj.opt("data");
  if(data instanceof JSONArray)return (JSONArray)data;
  if(data instanceof JSONObject){arr=((JSONObject)data).optJSONArray("galleries");if(arr!=null)return arr;arr=((JSONObject)data).optJSONArray("items");if(arr!=null)return arr;}
  arr=obj.optJSONArray("items");if(arr!=null)return arr;
  return findArray(root,"galleries");
 }
 static ArrayList<JSONObject> paginationScopes(Object root){
  ArrayList<JSONObject> scopes=new ArrayList<>();
  if(root instanceof JSONObject){
   JSONObject top=(JSONObject)root;scopes.add(top);
   for(String key:new String[]{"meta","pagination","paging","pageInfo","page_info","links"}){
    JSONObject nested=top.optJSONObject(key);if(nested!=null)scopes.add(nested);
   }
   JSONObject data=top.optJSONObject("data");if(data!=null){
    scopes.add(data);
    for(String key:new String[]{"meta","pagination","paging","pageInfo","page_info","links"}){
     JSONObject nested=data.optJSONObject(key);if(nested!=null)scopes.add(nested);
    }
   }
  }
  return scopes;
 }
 static Object fieldFrom(Object root,String...keys){
  for(JSONObject object:paginationScopes(root))for(String key:keys){Object v=object.opt(key);if(v!=null&&v!=JSONObject.NULL)return v;}
  return null;
 }
 static Integer numberFrom(Object root,String...keys){
  Object v=fieldFrom(root,keys);if(v instanceof Number)return ((Number)v).intValue();
  if(v!=null)try{return Integer.parseInt(value(v));}catch(Exception ignored){}return null;
 }
 static boolean hasPaginationInfo(Object root){
  return fieldFrom(root,"page","currentPage","current_page","lastPage","totalPages","total_pages","last_page","nextPage","next_page","hasNextPage","has_more","hasMore","nextCursor","next_cursor")!=null;
 }
 static boolean paginationEnded(Object root){
  Object more=fieldFrom(root,"hasNextPage","hasMore","has_more");
  if(more instanceof Boolean&&!((Boolean)more))return true;
  if(more instanceof String&&"false".equalsIgnoreCase((String)more))return true;
  Integer page=numberFrom(root,"page","currentPage","current_page");
  Integer total=numberFrom(root,"lastPage","totalPages","last_page","total_pages");
  return page!=null&&total!=null&&page>=total;
 }
 static String nextGalleryPath(Object root){
  Object next=fieldFrom(root,"next","nextPageUrl","next_page_url","nextPage","next_page","nextCursor","next_cursor");
  if(next==null)return null;
  if(next instanceof JSONObject){JSONObject o=(JSONObject)next;next=o.opt("href");if(next==null||next==JSONObject.NULL)next=o.opt("url");}
  if(next==null||next==JSONObject.NULL||next instanceof Boolean)return null;
  String v=value(next).trim();if(v.isEmpty()||"null".equalsIgnoreCase(v))return null;
  if(v.matches("[0-9]+"))return "/me/galleries?page="+v;
  if(v.startsWith("?"))return "/me/galleries"+v;
  if(v.startsWith("http://")||v.startsWith("https://")){
   try{URI uri=new URI(v);if(!"https".equalsIgnoreCase(uri.getScheme())||!"api.fotto.com.br".equalsIgnoreCase(uri.getHost()))return null;v=uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());}catch(Exception e){return null;}
  }
  if(v.startsWith("/api/me/galleries"))v=v.substring(4);
  if(v.startsWith("/me/galleries")&&(v.length()==13||v.charAt(13)=='?'))return v;
  if(!v.contains("/")&&!v.contains("?"))return "/me/galleries?cursor="+Uri.encode(v);
  return null;
 }
 static Gallery galleryById(Context c,String id)throws Exception{
  if(id==null||!id.matches("[A-Za-z0-9_-]{3,120}"))throw new IOException("Informe um ID de evento válido, obtido no painel Fotto.");
  Object root=new JSONTokener(call(c,"GET","/me/galleries/"+Uri.encode(id),null)).nextValue();
  JSONObject gallery=null;
  if(root instanceof JSONObject){
   JSONObject top=(JSONObject)root;
   gallery=top.optJSONObject("gallery");
   if(gallery==null){JSONObject data=top.optJSONObject("data");if(data!=null)gallery=data.optJSONObject("gallery")==null?data:data.optJSONObject("gallery");}
   if(gallery==null)gallery=top;
  }
  if(gallery==null||!id.equals(value(gallery.opt("id"))))throw new IOException("O Fotto não confirmou o ID deste evento. Confira o ID no painel do fotógrafo.");
  String title=gallery.optString("title",gallery.optString("name","Evento "+id));
  return new Gallery(id,title.isEmpty()?"Evento "+id:title);
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
  item.put("mediaSize",size);
  JSONArray body=new JSONArray();
  body.put(item);

  String path="/me/galleries/"+Uri.encode(galleryId)+"/medias";
  String response;
  try{
   // O HAR real do Fotto cria a mídia com uma lista contendo apenas
   // originalFileName + mediaSize. A autenticação usa o modo validado
   // durante o login: API key direta ou access token Bearer.
   response=call(ctx,"POST",path,body.toString());
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
 static HashMap<String,String> processedMedia(Context ctx,String galleryId)throws Exception{
  String json=call(ctx,"GET","/me/galleries/"+Uri.encode(galleryId)+"/medias",null);Object root=new JSONTokener(json).nextValue();JSONArray arr=findArray(root,"medias");
  if(arr==null&&root instanceof JSONObject){Object d=((JSONObject)root).opt("data");if(d instanceof JSONArray)arr=(JSONArray)d;else if(d!=null)arr=findArray(d,"medias");}
  HashMap<String,String> out=new HashMap<>();if(arr==null)return out;
  for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null||!m.optBoolean("processed",false))continue;String id=value(m.opt("id")),original=m.optString("originalFileName",m.optString("name",""));if(!id.isEmpty())out.put(id,original);}
  return out;
 }
 static void revalidateGallery(String galleryId){HttpURLConnection c=null;try{String u="https://www.fotto.com.br/api/revalidate/medias/"+Uri.encode(galleryId)+"?type=image";c=(HttpURLConnection)new URL(u).openConnection();c.setRequestMethod("GET");c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setUseCaches(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Lumo/0.15.21 Android");int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();if(in!=null)read(in,65536);}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}}
 static String confirmMedia(Context ctx,String galleryId,String expectedId,String name)throws Exception{
  String json=call(ctx,"GET","/me/galleries/"+Uri.encode(galleryId)+"/medias",null);Object root=new JSONTokener(json).nextValue();JSONArray arr=findArray(root,"medias");
  if(arr==null&&root instanceof JSONObject){Object d=((JSONObject)root).opt("data");if(d instanceof JSONArray)arr=(JSONArray)d;else if(d!=null)arr=findArray(d,"medias");}
  if(arr==null)return "";
  for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;String id=value(m.opt("id")),original=m.optString("originalFileName",m.optString("name",""));boolean processed=m.optBoolean("processed",false);
   if(!processed)continue;
   if(expectedId!=null&&!expectedId.isEmpty()&&expectedId.equals(id))return id;
   if(name!=null&&!name.isEmpty()&&name.equalsIgnoreCase(original))return id;
  }
  return "";
 }
 static String findExistingMedia(Context ctx,String galleryId,String name){
  try{String json=call(ctx,"GET","/me/galleries/"+Uri.encode(galleryId)+"/medias",null);Object root=new JSONTokener(json).nextValue();JSONArray arr=findArray(root,"medias");if(arr==null&&root instanceof JSONObject){Object d=((JSONObject)root).opt("data");if(d instanceof JSONArray)arr=(JSONArray)d;else if(d!=null)arr=findArray(d,"medias");}if(arr==null)return "";for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;String original=m.optString("originalFileName",m.optString("name",""));if(name.equalsIgnoreCase(original))return value(m.opt("id"));}}catch(Exception ignored){}return "";
 }
 static void put(Context ctx,Uri source,String name,long size,String url)throws IOException{
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("PUT");c.setConnectTimeout(20000);c.setReadTimeout(60000);c.setUseCaches(false);c.setDoOutput(true);String mime=ctx.getContentResolver().getType(source);if(mime==null||mime.isEmpty())mime=name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".png")?"image/png":"image/jpeg";c.setRequestProperty("Content-Type",mime);c.setRequestProperty("Content-Disposition","attachment; filename*=UTF-8''"+Uri.encode(name));if(size>0)c.setFixedLengthStreamingMode(size);else c.setChunkedStreamingMode(128*1024);
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
