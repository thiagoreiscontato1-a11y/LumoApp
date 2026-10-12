package com.hisho.tether;
import android.app.*;import android.content.*;import android.media.*;import android.os.*;

/** Alerta discreto para falhas operacionais no Modo Evento, com intervalo para evitar repetição. */
final class EventAlert{
 static synchronized void signal(Context c,String key,String message){
  if(c==null)return;Context app=c.getApplicationContext();android.content.SharedPreferences p=app.getSharedPreferences("hisho",0);if(!p.getBoolean("eventMode",false))return;long now=System.currentTimeMillis(),last=p.getLong("eventAlert_"+key,0);if(now-last<60000)return;p.edit().putLong("eventAlert_"+key,now).apply();
  try{ToneGenerator t=new ToneGenerator(AudioManager.STREAM_NOTIFICATION,85);t.startTone(ToneGenerator.TONE_SUP_ERROR,420);new Handler(Looper.getMainLooper()).postDelayed(t::release,650);}catch(Exception ignored){}
  try{NotificationManager n=app.getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel("lumo_event_alerts","Alertas do modo evento",NotificationManager.IMPORTANCE_DEFAULT);ch.setSound(null,null);n.createNotificationChannel(ch);PendingIntent open=PendingIntent.getActivity(app,130,new Intent(app,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);n.notify(130,new Notification.Builder(app,"lumo_event_alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("LUMO · atenção").setContentText(message).setContentIntent(open).setAutoCancel(true).build());}catch(Exception ignored){}
 }
 private EventAlert(){}
}
