package com.hisho.tether;
import android.content.*;import android.net.Uri;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;
final class FottoSync{
 private static final ExecutorService EXEC=Executors.newSingleThreadExecutor();private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
 static void kick(Context c){long after=c.getSharedPreferences("hisho",0).getLong("fottoStartRowid",Long.MAX_VALUE);kick(c,after,false);}
 static void sendExisting(Context c){kick(c,0,true);}
 static void kick(Context source,long after,boolean resetErrors){Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);if(!p.getBoolean("fottoAuto",false)&&!resetErrors)return;if(FottoApi.token(c).isEmpty()||p.getString("fottoGalleryId","").isEmpty())return;if(!RUNNING.compareAndSet(false,true))return;EXEC.execute(()->run(c,after,resetErrors));}
 private static void run(Context c,long after,boolean resetErrors){Jobs jobs=new Jobs(c);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId",""),title=p.getString("fottoGalleryTitle","Evento");try{jobs.fottoRecover(gallery);if(resetErrors)jobs.fottoRetry(gallery);int sent=0;while(sent<40){String[] x=jobs.nextFotto(gallery,after);if(x==null)break;String id=x[0],name=x[1],uri=x[2];try{jobs.fottoSet(id,gallery,"uploading","","",true);p.edit().putString("fottoLastStatus","Enviando "+name+" → "+title).apply();FottoApi.Uploaded done=FottoApi.upload(c,Uri.parse(uri),name,gallery);jobs.fottoSet(id,gallery,"done",done.mediaId,"",false);int total=p.getInt("fottoUploadedCount",0)+1;p.edit().putInt("fottoUploadedCount",total).putString("fottoLastStatus","Enviado · "+name).putLong("fottoLastUploadAt",System.currentTimeMillis()).apply();sent++;}catch(Exception e){jobs.fottoSet(id,gallery,"error","",e.getMessage()==null?e.toString():e.getMessage(),false);p.edit().putString("fottoLastStatus","Falha no Fotto · "+(e.getMessage()==null?e.toString():e.getMessage())).apply();break;}}
   if(sent==0&&resetErrors)p.edit().putString("fottoLastStatus","Nenhuma Editada pendente para este evento.").apply();
  }catch(Exception e){p.edit().putString("fottoLastStatus","Falha na fila Fotto · "+e.getMessage()).apply();}finally{jobs.close();RUNNING.set(false);}}
 static boolean running(){return RUNNING.get();}
}
