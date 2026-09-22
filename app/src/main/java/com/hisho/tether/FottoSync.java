package com.hisho.tether;
import android.content.*;import android.net.Uri;import android.os.SystemClock;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;

final class FottoSync{
 private static final ExecutorService EXEC=Executors.newSingleThreadExecutor();private static final AtomicBoolean RUNNING=new AtomicBoolean(false);private static final AtomicBoolean STOP_REQUESTED=new AtomicBoolean(false);
 static void kick(Context c){long after=c.getSharedPreferences("hisho",0).getLong("fottoStartRowid",Long.MAX_VALUE);kick(c,after,false);}
 static void sendExisting(Context c){STOP_REQUESTED.set(false);kick(c,0,true);}
 static void kick(Context source,long after,boolean resetErrors){Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);if(!p.getBoolean("fottoAuto",false)&&!resetErrors)return;if(FottoApi.token(c).isEmpty()||p.getString("fottoGalleryId","").isEmpty())return;if(!RUNNING.compareAndSet(false,true))return;STOP_REQUESTED.set(false);EXEC.execute(()->run(c,after,resetErrors));}
 static void sendSelected(Context source,Collection<String> ids){STOP_REQUESTED.set(false);if(ids==null||ids.isEmpty())return;Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);if(FottoApi.token(c).isEmpty()||p.getString("fottoGalleryId","").isEmpty()){p.edit().putString("fottoLastStatus","Conecte o Fotto e escolha um evento antes de enviar.").apply();return;}if(!RUNNING.compareAndSet(false,true))return;ArrayList<String> copy=new ArrayList<>(ids);EXEC.execute(()->runSelected(c,copy));}

 static void stop(Context source){Context c=source.getApplicationContext();STOP_REQUESTED.set(true);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);p.edit().putBoolean("fottoAuto",false).putString("fottoLastStatus",RUNNING.get()?"Encerrando envio após a foto atual…":"Envio encerrado").apply();}
 static boolean stopRequested(){return STOP_REQUESTED.get();}

 static void reconcile(Context source){
  Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId","");
  if(gallery.isEmpty()||FottoApi.token(c).isEmpty())return;
  if(!RUNNING.compareAndSet(false,true))return;
  EXEC.execute(()->{Jobs jobs=new Jobs(c);try{confirmOutstanding(c,jobs,gallery,false);}finally{jobs.close();RUNNING.set(false);}});
 }

 private static boolean confirmWithPolling(Context c,String gallery,String mediaId,String name)throws Exception{
  for(int i=0;i<5;i++){String found=FottoApi.confirmMedia(c,gallery,mediaId,name);if(found!=null&&!found.isEmpty())return true;if(i<4)SystemClock.sleep(1800);}
  return false;
 }
 private static void confirmed(Context c,Jobs jobs,String id,String gallery,String mediaId,String name){
  jobs.fottoSet(id,gallery,"confirmed",mediaId,"",false);jobs.history(id,System.currentTimeMillis(),"Fotto confirmado","Mídia confirmada no evento · ID "+mediaId);
  android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);int total=jobs.fottoDone(gallery);p.edit().putInt("fottoUploadedCount",total).putString("fottoLastStatus","Confirmada no Fotto · "+name).putLong("fottoLastUploadAt",System.currentTimeMillis()).apply();
 }
 private static void confirmOutstanding(Context c,Jobs jobs,String gallery,boolean quiet){
  ArrayList<String[]> rows=jobs.fottoNeedsConfirmation(gallery);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);
  for(String[] x:rows){if(STOP_REQUESTED.get())break;String id=x[0],name=x[1],mediaId=x[2];try{if(confirmWithPolling(c,gallery,mediaId,name)){confirmed(c,jobs,id,gallery,mediaId,name);}else{jobs.fottoSet(id,gallery,"unconfirmed",mediaId,"Envio concluído, mas a foto ainda não foi localizada no evento.",false);jobs.history(id,System.currentTimeMillis(),"Fotto não confirmado","Envio terminou, porém a mídia ainda não apareceu no evento.");if(!quiet)p.edit().putString("fottoLastStatus","Não confirmada no Fotto · "+name).apply();}}catch(Exception e){jobs.fottoSet(id,gallery,"unconfirmed",mediaId,e.getMessage(),false);if(!quiet)p.edit().putString("fottoLastStatus","Falha ao confirmar no Fotto · "+e.getMessage()).apply();QueueRecovery.schedule(c);}}
 }

 private static void run(Context c,long after,boolean resetErrors){
  Jobs jobs=new Jobs(c);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId",""),title=p.getString("fottoGalleryTitle","Evento");
  try{
   jobs.fottoRecover(gallery);if(resetErrors)jobs.fottoRetry(gallery);confirmOutstanding(c,jobs,gallery,true);
   int handled=0;while(handled<40){if(STOP_REQUESTED.get())break;String[] x=jobs.nextFotto(gallery,after);if(x==null)break;String id=x[0],name=x[1],uri=x[2];
    try{
     jobs.fottoSet(id,gallery,"uploading","","",true);jobs.history(id,System.currentTimeMillis(),"Envio iniciado","Enviando para "+title);p.edit().putString("fottoLastStatus","Enviando "+name+" → "+title).apply();
     FottoApi.Uploaded done=FottoApi.upload(c,Uri.parse(uri),name,gallery);jobs.fottoSet(id,gallery,"uploaded",done.mediaId,"Aguardando confirmação no evento.",false);jobs.history(id,System.currentTimeMillis(),"Envio concluído","Binário recebido pelo Fotto; aguardando confirmação no evento.");
     p.edit().putString("fottoLastStatus","Processando no Fotto · "+name).apply();
     if(confirmWithPolling(c,gallery,done.mediaId,name))confirmed(c,jobs,id,gallery,done.mediaId,name);
     else{jobs.fottoSet(id,gallery,"unconfirmed",done.mediaId,"Envio concluído, mas a foto não apareceu no evento após as tentativas de confirmação.",false);p.edit().putString("fottoLastStatus","Não confirmada no Fotto · "+name).apply();}
     handled++;
    }catch(Exception e){jobs.fottoSet(id,gallery,"error","",e.getMessage()==null?e.toString():e.getMessage(),false);jobs.history(id,System.currentTimeMillis(),"Falha no Fotto",e.getMessage()==null?e.toString():e.getMessage());p.edit().putString("fottoLastStatus","Falha no Fotto · "+(e.getMessage()==null?e.toString():e.getMessage())).apply();EventAlert.signal(c,"fotto","Falha no envio para o Fotto: "+(e.getMessage()==null?e.toString():e.getMessage()));QueueRecovery.schedule(c);break;}
   }
   if(STOP_REQUESTED.get())p.edit().putString("fottoLastStatus","Envio encerrado").apply();else if(handled==0&&resetErrors)p.edit().putString("fottoLastStatus","Nenhuma foto aprovada pendente para este evento.").apply();
  }catch(Exception e){p.edit().putString("fottoLastStatus","Falha na fila Fotto · "+e.getMessage()).apply();QueueRecovery.schedule(c);}finally{jobs.close();RUNNING.set(false);}
 }

 private static void runSelected(Context c,ArrayList<String> ids){
  Jobs jobs=new Jobs(c);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId",""),title=p.getString("fottoGalleryTitle","Evento");int sent=0,skipped=0;
  try{
   confirmOutstanding(c,jobs,gallery,true);
   for(String id:ids){if(STOP_REQUESTED.get())break;String[] j=jobs.editorJob(id);if(j==null||!"done".equals(j[5])||j[4]==null||j[4].isEmpty()){skipped++;continue;}if(jobs.fottoWasSent(id,gallery)){skipped++;continue;}String name=j[1].replaceFirst("(?i)\\.jpg$","_Editada.jpg");
    try{jobs.fottoSet(id,gallery,"uploading","","",true);jobs.history(id,System.currentTimeMillis(),"Envio iniciado","Envio manual para "+title);p.edit().putString("fottoLastStatus","Enviando "+name+" → "+title).apply();FottoApi.Uploaded done=FottoApi.upload(c,Uri.parse(j[4]),name,gallery);jobs.fottoSet(id,gallery,"uploaded",done.mediaId,"Aguardando confirmação.",false);
     if(confirmWithPolling(c,gallery,done.mediaId,name)){confirmed(c,jobs,id,gallery,done.mediaId,name);sent++;}else{jobs.fottoSet(id,gallery,"unconfirmed",done.mediaId,"Foto não localizada no evento após o envio.",false);}
    }catch(Exception e){jobs.fottoSet(id,gallery,"error","",e.getMessage()==null?e.toString():e.getMessage(),false);p.edit().putString("fottoLastStatus","Falha no Fotto · "+(e.getMessage()==null?e.toString():e.getMessage())).apply();EventAlert.signal(c,"fotto","Falha no envio para o Fotto: "+(e.getMessage()==null?e.toString():e.getMessage()));QueueRecovery.schedule(c);break;}
   }
   if(STOP_REQUESTED.get())p.edit().putString("fottoLastStatus","Envio encerrado"+(sent>0?" · "+sent+" foto(s) concluída(s)":"")).apply();else if(sent>0)p.edit().putString("fottoLastStatus",sent+" foto(s) confirmada(s) no Fotto"+(skipped>0?" · "+skipped+" ignorada(s)":"")).apply();else if(skipped>0)p.edit().putString("fottoLastStatus","As fotos selecionadas já estavam confirmadas ou ainda não estão aprovadas.").apply();
  }finally{jobs.close();RUNNING.set(false);}
 }
 static boolean running(){return RUNNING.get();}
}
