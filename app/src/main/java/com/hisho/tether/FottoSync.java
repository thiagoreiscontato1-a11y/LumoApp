package com.hisho.tether;
import android.content.*;import android.net.Uri;import android.os.SystemClock;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;

final class FottoSync{
 private static final ExecutorService EXEC=Executors.newSingleThreadExecutor();
 private static final AtomicBoolean RUNNING=new AtomicBoolean(false),STOP_REQUESTED=new AtomicBoolean(false);
 private static final int RECONCILE_EVERY=15;

 static void kick(Context c){long after=c.getSharedPreferences("hisho",0).getLong("fottoStartRowid",Long.MAX_VALUE);kick(c,after,false);}
 static void sendExisting(Context c){STOP_REQUESTED.set(false);kick(c,0,true);}
 static void kick(Context source,long after,boolean resetErrors){Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);if(!p.getBoolean("fottoAuto",false)&&!resetErrors)return;if(FottoApi.token(c).isEmpty()||p.getString("fottoGalleryId","").isEmpty())return;if(!RUNNING.compareAndSet(false,true))return;STOP_REQUESTED.set(false);EXEC.execute(()->run(c,after,resetErrors));}
 static void sendSelected(Context source,Collection<String> ids){STOP_REQUESTED.set(false);if(ids==null||ids.isEmpty())return;Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);if(FottoApi.token(c).isEmpty()||p.getString("fottoGalleryId","").isEmpty()){p.edit().putString("fottoLastStatus","Conecte o Fotto e escolha um evento antes de enviar.").apply();return;}if(!RUNNING.compareAndSet(false,true))return;EXEC.execute(()->runSelected(c,new ArrayList<>(ids)));}
 static void stop(Context source){Context c=source.getApplicationContext();STOP_REQUESTED.set(true);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);p.edit().putBoolean("fottoAuto",false).putString("fottoLastStatus",RUNNING.get()?"Encerrando envio após a foto atual…":"Envio encerrado").apply();}
 static boolean stopRequested(){return STOP_REQUESTED.get();}

 static void reconcile(Context source){Context c=source.getApplicationContext();android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId","");if(gallery.isEmpty()||FottoApi.token(c).isEmpty())return;if(!RUNNING.compareAndSet(false,true))return;EXEC.execute(()->{Jobs jobs=new Jobs(c);try{reconcileBatch(c,jobs,gallery,false);}finally{jobs.close();RUNNING.set(false);}});}

 private static void confirmed(Context c,Jobs jobs,String id,String gallery,String mediaId,String name){
  jobs.fottoSet(id,gallery,"confirmed",mediaId,"",false);jobs.history(id,System.currentTimeMillis(),"Fotto confirmado","Mídia confirmada no evento · ID "+mediaId);
  android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);int total=jobs.fottoDone(gallery);p.edit().putInt("fottoUploadedCount",total).putString("fottoLastStatus","Confirmada no Fotto · "+name).putLong("fottoLastUploadAt",System.currentTimeMillis()).apply();
 }

 /*
  * Confirma várias fotos com UMA única leitura da galeria.
  * Antes, cada foto fazia até 5 GETs /medias. Em eventos grandes isso gerava
  * centenas de consultas e podia provocar lentidão/rate-limit perto de 100 fotos.
  */
 private static int reconcileBatch(Context c,Jobs jobs,String gallery,boolean quiet){
  ArrayList<String[]> rows=jobs.fottoNeedsConfirmation(gallery);if(rows.isEmpty())return 0;android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);
  try{
   FottoApi.revalidateGallery(gallery);HashMap<String,String> visible=FottoApi.processedMedia(c,gallery);if(visible.isEmpty())return 0;
   HashMap<String,String> byName=new HashMap<>();for(Map.Entry<String,String> e:visible.entrySet())if(e.getValue()!=null&&!e.getValue().isEmpty())byName.put(e.getValue().toLowerCase(Locale.ROOT),e.getKey());
   int confirmed=0;
   for(String[] x:rows){String id=x[0],name=x[1],mediaId=x[2];String found=!mediaId.isEmpty()&&visible.containsKey(mediaId)?mediaId:byName.get(name.toLowerCase(Locale.ROOT));if(found!=null&&!found.isEmpty()){confirmed(c,jobs,id,gallery,found,name);confirmed++;}}
   if(confirmed>0&&!quiet)p.edit().putString("fottoLastStatus",confirmed+" foto(s) confirmada(s) no Fotto").apply();return confirmed;
  }catch(Exception e){if(!quiet)p.edit().putString("fottoLastStatus","Confirmação adiada · "+(e.getMessage()==null?e.toString():e.getMessage())).apply();return 0;}
 }

 private static void run(Context c,long after,boolean resetErrors){
  Jobs jobs=new Jobs(c);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId",""),title=p.getString("fottoGalleryTitle","Evento");int handled=0;boolean failed=false;
  try{
   jobs.fottoRecover(gallery);if(resetErrors)jobs.fottoRetry(gallery);
   // Só uma reconciliação leve no começo; nunca bloqueia a fila por foto.
   reconcileBatch(c,jobs,gallery,true);
   while(!STOP_REQUESTED.get()){
    String[] x=jobs.nextFotto(gallery,after);if(x==null)break;String id=x[0],name=x[1],uri=x[2];
    try{
     jobs.fottoSet(id,gallery,"uploading","","",true);jobs.history(id,System.currentTimeMillis(),"Envio iniciado","Enviando para "+title);p.edit().putString("fottoLastStatus","Enviando "+name+" → "+title).apply();
     FottoApi.Uploaded done=FottoApi.upload(c,Uri.parse(uri),name,gallery);
     // PUT 2xx: o binário já foi aceito. Daqui em diante NUNCA reenviar a foto automaticamente.
     jobs.fottoSet(id,gallery,"uploaded",done.mediaId,"Aguardando processamento/confirmacao no evento.",false);jobs.history(id,System.currentTimeMillis(),"Envio aceito","Arquivo recebido pelo armazenamento do Fotto · mídia "+done.mediaId);
     handled++;int accepted=jobs.fottoAccepted(gallery);p.edit().putString("fottoLastStatus","Enviada "+accepted+" · processando no Fotto · "+name).putLong("fottoLastUploadAt",System.currentTimeMillis()).apply();
     // Uma confirmação em lote a cada várias fotos, sem segurar cada upload.
     if(handled%RECONCILE_EVERY==0)reconcileBatch(c,jobs,gallery,true);
    }catch(Exception e){String msg=e.getMessage()==null?e.toString():e.getMessage();jobs.fottoSet(id,gallery,"error","",msg,false);jobs.history(id,System.currentTimeMillis(),"Falha no Fotto",msg);p.edit().putString("fottoLastStatus","Falha no Fotto · "+msg).apply();EventAlert.signal(c,"fotto","Falha no envio para o Fotto: "+msg);QueueRecovery.schedule(c);failed=true;break;}
   }
   if(handled>0&&!STOP_REQUESTED.get()){SystemClock.sleep(1200);reconcileBatch(c,jobs,gallery,true);}
   if(STOP_REQUESTED.get())p.edit().putString("fottoLastStatus","Envio encerrado").apply();else if(handled==0&&resetErrors)p.edit().putString("fottoLastStatus","Nenhuma foto aprovada pendente para este evento.").apply();else if(handled>0&&!failed)p.edit().putString("fottoLastStatus","Fila atual enviada · "+jobs.fottoAccepted(gallery)+" aceita(s) pelo Fotto").apply();
  }catch(Exception e){p.edit().putString("fottoLastStatus","Falha na fila Fotto · "+e.getMessage()).apply();QueueRecovery.schedule(c);failed=true;}
  finally{
   jobs.close();RUNNING.set(false);
   if(!STOP_REQUESTED.get()&&p.getBoolean("fottoAuto",false))safetyKick(c,after);
  }
 }

 /* Fecha a pequena corrida em que uma foto pode terminar a edição exatamente
  * entre o último SELECT da fila e RUNNING=false. */
 private static void safetyKick(Context c,long after){
  EXEC.execute(()->{SystemClock.sleep(450);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);if(STOP_REQUESTED.get()||!p.getBoolean("fottoAuto",false))return;String gallery=p.getString("fottoGalleryId","");if(gallery.isEmpty())return;Jobs j=new Jobs(c);boolean pending=false;try{pending=j.fottoUploadPending(gallery,after)>0;}finally{j.close();}if(pending)kick(c,after,false);});
 }

 private static void runSelected(Context c,ArrayList<String> ids){
  Jobs jobs=new Jobs(c);android.content.SharedPreferences p=c.getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId",""),title=p.getString("fottoGalleryTitle","Evento");int sent=0,skipped=0;
  try{
   reconcileBatch(c,jobs,gallery,true);
   for(String id:ids){if(STOP_REQUESTED.get())break;String[] j=jobs.editorJob(id);if(j==null||!"done".equals(j[5])||j[4]==null||j[4].isEmpty()){skipped++;continue;}if(jobs.fottoWasSent(id,gallery)){skipped++;continue;}String[] state=jobs.fottoState(id,gallery);if("uploaded".equals(state[0])||"unconfirmed".equals(state[0])){skipped++;continue;}String name=j[1].replaceFirst("(?i)\\.jpg$","_Editada.jpg");
    try{jobs.fottoSet(id,gallery,"uploading","","",true);jobs.history(id,System.currentTimeMillis(),"Envio iniciado","Envio manual para "+title);p.edit().putString("fottoLastStatus","Enviando "+name+" → "+title).apply();FottoApi.Uploaded done=FottoApi.upload(c,Uri.parse(j[4]),name,gallery);jobs.fottoSet(id,gallery,"uploaded",done.mediaId,"Aguardando processamento/confirmacao.",false);jobs.history(id,System.currentTimeMillis(),"Envio aceito","Arquivo recebido pelo armazenamento do Fotto · mídia "+done.mediaId);sent++;if(sent%RECONCILE_EVERY==0)reconcileBatch(c,jobs,gallery,true);
    }catch(Exception e){String msg=e.getMessage()==null?e.toString():e.getMessage();jobs.fottoSet(id,gallery,"error","",msg,false);p.edit().putString("fottoLastStatus","Falha no Fotto · "+msg).apply();EventAlert.signal(c,"fotto","Falha no envio para o Fotto: "+msg);QueueRecovery.schedule(c);break;}
   }
   if(sent>0){SystemClock.sleep(1200);reconcileBatch(c,jobs,gallery,true);}if(STOP_REQUESTED.get())p.edit().putString("fottoLastStatus","Envio encerrado"+(sent>0?" · "+sent+" foto(s) enviada(s)":"")).apply();else if(sent>0)p.edit().putString("fottoLastStatus",sent+" foto(s) aceita(s) pelo Fotto"+(skipped>0?" · "+skipped+" ignorada(s)":"")).apply();else if(skipped>0)p.edit().putString("fottoLastStatus","As fotos selecionadas já estavam enviadas ou ainda não estão aprovadas.").apply();
  }finally{jobs.close();RUNNING.set(false);}
 }
 static boolean running(){return RUNNING.get();}
}
