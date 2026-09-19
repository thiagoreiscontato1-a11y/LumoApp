package com.hisho.tether;

import android.app.*;
import android.content.*;
import android.os.*;

public class FottoKeepAliveService extends Service {
 static final String CHANNEL="lumo_fotto_monitor";
 static final int NOTIFICATION_ID=7345;
 final Handler handler=new Handler(Looper.getMainLooper());
 PowerManager.WakeLock wakeLock;

 final Runnable tick=new Runnable(){public void run(){
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);
  boolean active=p.getBoolean("fottoWebActive",false),paused=p.getBoolean("fottoWebPaused",false);
  long updated=p.getLong("fottoWebUpdatedAt",0),age=updated<=0?Long.MAX_VALUE:System.currentTimeMillis()-updated;
  if((!active&&!paused&&age>60000)||age>300000){stopSelf();return;}
  try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification());}catch(Exception ignored){}
  handler.postDelayed(this,2500);
 }};

 @Override public void onCreate(){
  super.onCreate();
  NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  if(Build.VERSION.SDK_INT>=26){
   NotificationChannel c=new NotificationChannel(CHANNEL,"Monitoramento Fotto",NotificationManager.IMPORTANCE_LOW);
   c.setDescription("Mantém o monitoramento do Fotto ativo enquanto você usa o Lumo.");
   nm.createNotificationChannel(c);
  }
  try{
   PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
   wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LUMO:FottoMonitor");
   wakeLock.setReferenceCounted(false);wakeLock.acquire();
  }catch(Exception ignored){}
 }

 @Override public int onStartCommand(Intent intent,int flags,int startId){
  startForeground(NOTIFICATION_ID,notification());
  handler.removeCallbacks(tick);handler.post(tick);
  return START_STICKY;
 }

 Notification notification(){
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);
  int loaded=p.getInt("fottoWebLoaded",-1),queue=p.getInt("fottoWebQueue",-1),errors=p.getInt("fottoWebErrors",-1);
  String event=p.getString("fottoWebEventId",""),duration=p.getString("fottoWebActiveFor","");
  long updated=p.getLong("fottoWebUpdatedAt",0),age=updated<=0?Long.MAX_VALUE:System.currentTimeMillis()-updated;
  boolean active=p.getBoolean("fottoWebActive",false),paused=p.getBoolean("fottoWebPaused",false);
  String title=active?"Fotto monitorando em segundo plano":paused?"Fotto pausado":"Acompanhamento do Fotto";
  if(age>20000&&active)title="Fotto sem atualização";
  String text=(event.isEmpty()?"Evento":"Evento "+event)+" · "+n(loaded)+" carregadas · "+n(queue)+" na fila · "+n(errors)+" erros";
  if(active&&!duration.isEmpty())text+=" · "+duration;
  Intent open=new Intent(this,FottoWebActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent pi=PendingIntent.getActivity(this,44,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
  return b.setSmallIcon(R.drawable.ic_lumo).setContentTitle(title).setContentText(text).setContentIntent(pi).setOngoing(active).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
 }

 String n(int v){return v<0?"—":String.valueOf(v);}

 @Override public void onDestroy(){
  handler.removeCallbacks(tick);
  try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}
  super.onDestroy();
 }

 @Override public android.os.IBinder onBind(Intent i){return null;}
}
