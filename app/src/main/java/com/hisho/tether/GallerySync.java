package com.hisho.tether;

import android.content.*;
import android.net.Uri;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fila da galeria online: publica cada foto aprovada do evento ativo na galeria ligada a ele.
 * Corre ao lado da entrega Fotto (que continua igual), em sua própria thread.
 * É acionada junto com FottoSync.kick: nova foto aprovada, reedição, retomada da fila, abertura do app.
 */
final class GallerySync {
 private static final ExecutorService EXEC=Executors.newSingleThreadExecutor();
 private static final AtomicBoolean RUNNING=new AtomicBoolean(false),AGAIN=new AtomicBoolean(false);

 static SharedPreferences prefs(Context c){return c.getSharedPreferences("hisho",0);}
 static boolean auto(Context c){return prefs(c).getBoolean("lgAuto",true);}
 static void setAuto(Context c,boolean on){prefs(c).edit().putBoolean("lgAuto",on).apply();if(on)kick(c);}
 static String status(Context c){return prefs(c).getString("lgLastStatus","");}
 static boolean running(){return RUNNING.get();}

 /** Envio automático (respeita o interruptor). */
 static void kick(Context source){start(source,false);}

 /** "Enviar pendentes agora": envia mesmo com o automático desligado e zera as tentativas com erro. */
 static void sendNow(Context source){start(source,true);}

 private static void start(Context source,boolean manual){
  Context c=source.getApplicationContext();
  if(!DemoLicense.allowed(c)||!GalleryApi.configured(c))return;
  if(!manual&&!auto(c))return;
  String event=prefs(c).getString("localEventId","");
  GalleryApi.Gallery g=GalleryApi.galleryFor(c,event);if(g==null)return;
  if(!RUNNING.compareAndSet(false,true)){AGAIN.set(true);return;}
  EXEC.execute(()->run(c,g,event,manual));
 }

 private static void run(Context c,GalleryApi.Gallery g,String event,boolean manual){
  Jobs jobs=new Jobs(c);SharedPreferences p=prefs(c);int sent=0;boolean failed=false;
  try{
   jobs.onlineRecover(g.id);if(manual)jobs.onlineRetry(g.id);
   while(DemoLicense.allowed(c)&&(manual||auto(c))){
    String[] x=jobs.nextOnline(g.id,event);if(x==null)break;
    String id=x[0],name=x[1],uri=x[2];long takenAt=Long.parseLong(x[3]),revision=Long.parseLong(x[4]);
    jobs.onlineSet(id,g.id,"uploading",revision,"","",true);
    p.edit().putString("lgLastStatus","Publicando "+name+" na galeria…").apply();
    try{
     String remote=GalleryApi.upload(c,g.id,id,revision,Uri.parse(uri),name,takenAt);
     jobs.onlineSet(id,g.id,"done",revision,remote,"",false);jobs.history(id,System.currentTimeMillis(),"Galeria online","Publicada em "+g.title);sent++;
     p.edit().putString("lgLastStatus","Publicada "+name+" · "+jobs.onlineDone(g.id)+" na galeria").putLong("lgLastUploadAt",System.currentTimeMillis()).apply();
    }catch(Exception e){
     String msg=e.getMessage()==null?e.toString():e.getMessage();
     jobs.onlineSet(id,g.id,"error",revision,"",msg,false);jobs.history(id,System.currentTimeMillis(),"Falha na galeria online",msg);
     p.edit().putString("lgLastStatus","Falha na galeria online · "+msg).apply();
     QueueRecovery.schedule(c);failed=true;break;
    }
   }
   if(!failed){int pending=jobs.onlinePending(g.id,event);if(sent>0||manual)p.edit().putString("lgLastStatus",pending==0?"Galeria em dia · "+jobs.onlineDone(g.id)+" foto(s) publicadas":pending+" foto(s) aguardando envio").apply();}
  }catch(Exception e){p.edit().putString("lgLastStatus","Falha na fila da galeria · "+e.getMessage()).apply();}
  finally{jobs.close();RUNNING.set(false);if(AGAIN.getAndSet(false)&&!failed)kick(c);}
 }

 private GallerySync(){}
}
