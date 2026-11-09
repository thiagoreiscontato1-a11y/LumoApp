package com.hisho.tether;
import android.app.*;import android.content.*;import android.os.*;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.text.SimpleDateFormat;import java.util.*;
import org.json.*;

/**
 * Relatório de falhas sem SDK (o projeto compila sem Gradle).
 *
 * Quando o Lumo fecha por erro, o relatório é gravado em files/crashes antes de o processo morrer. Na próxima abertura
 * ele é enviado ao Sentry pelo endpoint HTTP de envelopes, se o build tiver um DSN (lumo-build.json, gerado pelo build.py
 * a partir do segredo SENTRY_DSN) e o usuário não tiver desligado o envio. Travamentos (ANR) e falhas nativas da abertura
 * anterior vêm do ApplicationExitInfo (Android 11+). O último relatório fica legível para "Compartilhar" pelo WhatsApp.
 *
 * Nunca entra no relatório: fotos, conta Fotto, senhas, nomes de eventos (caminhos "Eventos/..." são mascarados) ou e-mails.
 */
final class CrashReporter {
 static final String PREFS="lumo_crash";
 static final int MAX_PENDING=20;
 static final long MAX_AGE=30L*24*60*60*1000;
 private static Context app;
 private static final ArrayDeque<String> trail=new ArrayDeque<>();
 private static volatile boolean installed;
 // Travas separadas: o handler de falha nunca espera um envio em andamento (que pode levar até 35 s sem rede).
 private static final Object FILES=new Object(),SENDING=new Object();

 /** Chamado em LumoApplication.onCreate, antes de qualquer outra coisa. */
 static synchronized void install(Application a){
  if(installed)return;installed=true;app=a;
  final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
  Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
   try{save(crashEvent(thread,error));}catch(Throwable ignored){}
   if(previous!=null)previous.uncaughtException(thread,error);else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
  });
  new Thread(()->{try{collectExitReasons();}catch(Throwable ignored){}try{sendPending();}catch(Throwable ignored){}},"lumo-crash-send").start();
 }

 /** Rastro curto do que o app fazia (telas abertas, captura ligada). Só nomes técnicos, nunca conteúdo do usuário. */
 static void note(String what){String line=iso(System.currentTimeMillis())+" "+scrub(what);synchronized(trail){if(trail.size()>=30)trail.removeFirst();trail.addLast(line);}}

 static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,0);}
 static boolean enabled(Context c){return prefs(c).getBoolean("send",true);}
 static void setEnabled(Context c,boolean on){prefs(c).edit().putBoolean("send",on).apply();if(on)new Thread(CrashReporter::sendPending,"lumo-crash-send").start();}
 static boolean configured(Context c){return !buildInfo(c).optString("sentryDsn","").isEmpty();}
 static File dir(Context c){File d=new File(c.getFilesDir(),"crashes");d.mkdirs();return d;}
 static File lastReport(Context c){return new File(dir(c),"ultimo-relatorio.txt");}
 static int pending(Context c){File[] f=dir(c).listFiles((d,n)->n.endsWith(".json"));return f==null?0:f.length;}

 /** Dados gravados pelo build.py: sentryDsn e signing ("producao" ou "teste"). */
 static JSONObject buildInfo(Context c){
  try(InputStream in=c.getAssets().open("lumo-build.json")){return new JSONObject(readAll(in,64*1024));}catch(Exception e){return new JSONObject();}
 }

 // ---------- montagem do evento (formato Sentry) ----------

 static JSONObject crashEvent(Thread thread,Throwable error)throws JSONException{
  JSONObject e=base("fatal");
  JSONArray values=new JSONArray();
  List<Throwable> chain=new ArrayList<>();for(Throwable t=error;t!=null&&chain.size()<8&&!chain.contains(t);t=t.getCause())chain.add(t);
  // Sentry espera a causa mais interna primeiro e a exceção lançada por último.
  for(int i=chain.size()-1;i>=0;i--){
   Throwable t=chain.get(i);JSONObject v=new JSONObject();
   String type=t.getClass().getName();int dot=type.lastIndexOf('.');
   v.put("type",dot<0?type:type.substring(dot+1));if(dot>0)v.put("module",type.substring(0,dot));
   v.put("value",scrub(String.valueOf(t.getMessage())));v.put("thread_id",thread.getId());
   v.put("stacktrace",new JSONObject().put("frames",frames(t.getStackTrace())));
   if(i==0)v.put("mechanism",new JSONObject().put("type","UncaughtExceptionHandler").put("handled",false));
   values.put(v);
  }
  e.put("exception",new JSONObject().put("values",values));
  e.getJSONObject("tags").put("thread",thread.getName());
  return e;
 }

 static JSONArray frames(StackTraceElement[] stack)throws JSONException{
  JSONArray frames=new JSONArray();int n=Math.min(stack.length,120);
  for(int i=n-1;i>=0;i--){StackTraceElement s=stack[i];JSONObject f=new JSONObject();
   f.put("module",s.getClassName());f.put("function",s.getMethodName());if(s.getFileName()!=null)f.put("filename",s.getFileName());
   if(s.getLineNumber()>0)f.put("lineno",s.getLineNumber());f.put("in_app",s.getClassName().startsWith("com.hisho."));frames.put(f);}
  return frames;
 }

 static JSONObject base(String level)throws JSONException{
  Context c=app;JSONObject e=new JSONObject();long now=System.currentTimeMillis();
  e.put("event_id",UUID.randomUUID().toString().replace("-",""));e.put("timestamp",now/1000.0);e.put("platform","java");e.put("level",level);
  String version="?";long code=0;try{android.content.pm.PackageInfo i=c.getPackageManager().getPackageInfo(c.getPackageName(),0);version=i.versionName;code=i.getLongVersionCode();}catch(Exception ignored){}
  JSONObject build=buildInfo(c);boolean demo=c.getPackageName().endsWith(".demo");
  e.put("release","lumo-android@"+version);e.put("dist",String.valueOf(code));
  e.put("environment",demo?"demo":build.optString("signing","teste"));
  e.put("user",new JSONObject().put("id",installId(c)));
  JSONObject tags=new JSONObject().put("pacote",c.getPackageName()).put("captura",String.valueOf(CaptureService.active!=null)).put("api",String.valueOf(Build.VERSION.SDK_INT));e.put("tags",tags);
  Runtime rt=Runtime.getRuntime();
  JSONObject contexts=new JSONObject();
  contexts.put("os",new JSONObject().put("name","Android").put("version",Build.VERSION.RELEASE).put("build",Build.DISPLAY));
  contexts.put("device",new JSONObject().put("manufacturer",Build.MANUFACTURER).put("brand",Build.BRAND).put("model",Build.MODEL).put("family",Build.DEVICE)
   .put("arch",Build.SUPPORTED_ABIS.length>0?Build.SUPPORTED_ABIS[0]:"").put("free_memory",rt.freeMemory()).put("memory_size",rt.maxMemory()).put("free_storage",c.getFilesDir().getUsableSpace()));
  contexts.put("app",new JSONObject().put("app_identifier",c.getPackageName()).put("app_version",version).put("app_build",String.valueOf(code)));
  e.put("contexts",contexts);
  JSONArray crumbs=new JSONArray();synchronized(trail){for(String s:trail)crumbs.put(new JSONObject().put("message",s).put("category","lumo"));}
  e.put("breadcrumbs",new JSONObject().put("values",crumbs));
  return e;
 }

 /** Evento de teste, para conferir se o Sentry está recebendo. */
 static void sendTest(Context c,Runnable done){
  new Thread(()->{try{JSONObject e=base("info");e.put("message",new JSONObject().put("formatted","Teste do relatório de falhas do Lumo"));save(e);sendPending();}catch(Throwable ignored){}
   if(done!=null)new Handler(Looper.getMainLooper()).post(done);},"lumo-crash-test").start();
 }

 /** ANR e falhas nativas da abertura anterior (Android 11+). As falhas Java já foram gravadas pelo handler. */
 static void collectExitReasons()throws Exception{
  if(Build.VERSION.SDK_INT<30)return;
  SharedPreferences p=prefs(app);long since=p.getLong("exitSince",0),newest=since;
  if(since==0){p.edit().putLong("exitSince",System.currentTimeMillis()).apply();return;}
  ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
  for(ApplicationExitInfo x:am.getHistoricalProcessExitReasons(null,0,10)){
   if(x.getTimestamp()<=since)continue;newest=Math.max(newest,x.getTimestamp());
   int r=x.getReason();if(r!=ApplicationExitInfo.REASON_ANR&&r!=ApplicationExitInfo.REASON_CRASH_NATIVE)continue;
   boolean anr=r==ApplicationExitInfo.REASON_ANR;
   JSONObject e=base(anr?"error":"fatal");e.put("timestamp",x.getTimestamp()/1000.0);
   e.put("message",new JSONObject().put("formatted",(anr?"ANR: o Lumo travou":"Falha nativa")+(x.getDescription()==null?"":" · "+scrub(x.getDescription()))));
   e.put("fingerprint",new JSONArray().put(anr?"anr":"native").put(String.valueOf(x.getDescription()).replaceAll("[0-9]+","#")));
   e.getJSONObject("tags").put("exit_reason",anr?"anr":"crash_native").put("importance",String.valueOf(x.getImportance()));
   e.remove("breadcrumbs");
   String trace="";try(InputStream in=x.getTraceInputStream()){if(in!=null&&anr)trace=mainThread(readAll(in,256*1024));}catch(Exception ignored){}
   if(!trace.isEmpty())e.put("extra",new JSONObject().put("anr_main_thread",scrub(trace)));
   save(e);
  }
  if(newest>since)p.edit().putLong("exitSince",newest).apply();
 }

 /** Do dump de ANR, só o bloco da thread principal (o resto pode ter centenas de KB). */
 static String mainThread(String dump){
  int start=dump.indexOf("\"main\"");if(start<0)return dump.substring(0,Math.min(dump.length(),8000));
  int end=dump.indexOf("\n\n",start);String s=dump.substring(start,end<0?dump.length():end);return s.substring(0,Math.min(s.length(),8000));
 }

 // ---------- fila local ----------

 static void save(JSONObject event)throws IOException{synchronized(FILES){
  File d=dir(app);
  try(Writer w=new OutputStreamWriter(new FileOutputStream(new File(d,event.optString("event_id")+".json")),StandardCharsets.UTF_8)){w.write(event.toString());}
  try(Writer w=new OutputStreamWriter(new FileOutputStream(lastReport(app)),StandardCharsets.UTF_8)){w.write(readable(event));}
  File[] all=d.listFiles((x,n)->n.endsWith(".json"));
  if(all!=null&&all.length>MAX_PENDING){Arrays.sort(all,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(int i=0;i<all.length-MAX_PENDING;i++)all[i].delete();}
 }}

 static void sendPending(){synchronized(SENDING){
  if(app==null)return;File[] files=dir(app).listFiles((x,n)->n.endsWith(".json"));if(files==null)return;
  long now=System.currentTimeMillis();
  for(File f:files)if(now-f.lastModified()>MAX_AGE)f.delete();
  String dsn=buildInfo(app).optString("sentryDsn","");if(dsn.isEmpty()||!enabled(app))return;
  for(File f:files){if(!f.exists())continue;
   try{int code=post(dsn,new JSONObject(readAll(new FileInputStream(f),1024*1024)));
    if(code>=200&&code<300||code==400||code==413)f.delete(); // 400/413: evento recusado; não adianta repetir.
    else if(code==429||code>=500||code<0)break; // tenta de novo na próxima abertura
   }catch(JSONException bad){f.delete();}catch(Exception e){break;}
  }
 }}

 /** POST no endpoint de envelopes do Sentry. DSN: https://CHAVE@host/ID_DO_PROJETO */
 static int post(String dsn,JSONObject event)throws Exception{
  URI u=URI.create(dsn.trim());String key=u.getUserInfo();String path=u.getPath()==null?"":u.getPath();
  int slash=path.lastIndexOf('/');String project=path.substring(slash+1),prefix=path.substring(0,Math.max(0,slash));
  if(key==null||key.isEmpty()||project.isEmpty())return 400;
  int colon=key.indexOf(':');if(colon>=0)key=key.substring(0,colon);
  URL url=new URL(u.getScheme()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"")+prefix+"/api/"+project+"/envelope/");
  byte[] body=envelope(dsn,event);
  HttpURLConnection h=(HttpURLConnection)url.openConnection();
  try{h.setConnectTimeout(15000);h.setReadTimeout(20000);h.setRequestMethod("POST");h.setDoOutput(true);h.setFixedLengthStreamingMode(body.length);
   h.setRequestProperty("Content-Type","application/x-sentry-envelope");
   h.setRequestProperty("X-Sentry-Auth","Sentry sentry_version=7, sentry_client=lumo-android/1.0, sentry_key="+key);
   try(OutputStream o=h.getOutputStream()){o.write(body);}
   return h.getResponseCode();
  }finally{h.disconnect();}
 }

 static byte[] envelope(String dsn,JSONObject event)throws Exception{
  byte[] item=event.toString().getBytes(StandardCharsets.UTF_8);
  String head=new JSONObject().put("event_id",event.optString("event_id")).put("sent_at",iso(System.currentTimeMillis())).put("dsn",dsn).toString()+"\n"
   +new JSONObject().put("type","event").put("length",item.length).toString()+"\n";
  ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(head.getBytes(StandardCharsets.UTF_8));out.write(item);out.write('\n');return out.toByteArray();
 }

 // ---------- utilitários ----------

 /** Texto para o usuário compartilhar quando o Sentry não está configurado. */
 static String readable(JSONObject e){
  StringBuilder s=new StringBuilder("Relatório de falha do Lumo\n");
  s.append(iso((long)(e.optDouble("timestamp",0)*1000))).append(" · ").append(e.optString("release")).append(" (").append(e.optString("dist")).append(") · ").append(e.optString("environment")).append('\n');
  JSONObject ctx=e.optJSONObject("contexts");
  if(ctx!=null){JSONObject d=ctx.optJSONObject("device"),os=ctx.optJSONObject("os");
   if(d!=null)s.append(d.optString("manufacturer")).append(' ').append(d.optString("model")).append(" · ");
   if(os!=null)s.append("Android ").append(os.optString("version"));s.append('\n');}
  JSONObject msg=e.optJSONObject("message");if(msg!=null)s.append('\n').append(msg.optString("formatted")).append('\n');
  JSONObject ex=e.optJSONObject("exception");
  if(ex!=null){JSONArray v=ex.optJSONArray("values");
   for(int i=v.length()-1;i>=0;i--){JSONObject x=v.optJSONObject(i);s.append('\n').append(i==v.length()-1?"":"Causado por: ").append(x.optString("module")).append('.').append(x.optString("type")).append(": ").append(x.optString("value")).append('\n');
    JSONArray f=x.optJSONObject("stacktrace").optJSONArray("frames");
    for(int j=f.length()-1;j>=Math.max(0,f.length()-25);j--){JSONObject fr=f.optJSONObject(j);s.append("  at ").append(fr.optString("module")).append('.').append(fr.optString("function")).append('(').append(fr.optString("filename")).append(':').append(fr.optInt("lineno")).append(")\n");}}}
  JSONObject extra=e.optJSONObject("extra");if(extra!=null&&extra.has("anr_main_thread"))s.append('\n').append(extra.optString("anr_main_thread")).append('\n');
  JSONObject bc=e.optJSONObject("breadcrumbs");
  if(bc!=null&&bc.optJSONArray("values").length()>0){s.append("\nÚltimos passos:\n");JSONArray v=bc.optJSONArray("values");for(int i=0;i<v.length();i++)s.append("  ").append(v.optJSONObject(i).optString("message")).append('\n');}
  return s.toString();
 }

 /** Remove o que pode identificar o cliente: nomes de eventos nos caminhos, e-mails, URIs de documentos e tokens. */
 static String scrub(String s){
  if(s==null)return "";
  return s.replaceAll("Eventos/[^/\\s:]+","Eventos/<evento>")
   .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","<email>")
   .replaceAll("content://[^\\s,;)]+","content://<uri>")
   .replaceAll("(?i)(token|bearer|authorization|api[_-]?key|password|senha)([\"'=: ]+)[^\\s,;\"']+","$1$2<oculto>");
 }

 static String installId(Context c){
  SharedPreferences p=prefs(c);String id=p.getString("installId","");
  if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("installId",id).apply();}return id;
 }

 static String iso(long ms){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(ms));}

 static String readAll(InputStream in,int max)throws IOException{
  try(InputStream i=in){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=i.read(b))>0&&o.size()<max)o.write(b,0,n);return new String(o.toByteArray(),StandardCharsets.UTF_8);}
 }
}
