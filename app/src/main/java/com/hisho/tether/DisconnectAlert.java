package com.hisho.tether;
import android.app.*;import android.content.*;import android.media.*;import android.os.*;import android.util.Log;
final class DisconnectAlert {
 private static final Handler handler=new Handler(Looper.getMainLooper());
 private static ToneGenerator tone;private static PowerManager.WakeLock wake;private static long generation;
 static void test(Context c){play(c.getApplicationContext());}
 static void show(Context c,String message){
  if(!c.getSharedPreferences("hisho",0).getBoolean("disconnectSound",true))return;
  play(c.getApplicationContext());
  // Visual notice is optional; notification permission cannot block direct audio.
  try{NotificationManager manager=c.getSystemService(NotificationManager.class);
   NotificationChannel channel=new NotificationChannel("camera_disconnected_visual","Aviso visual de desconexão",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);manager.createNotificationChannel(channel);
   PendingIntent open=PendingIntent.getActivity(c,4,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   manager.notify(2,new Notification.Builder(c,"camera_disconnected_visual").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("LUMO · câmera desconectada").setContentText(message).setContentIntent(open).setAutoCancel(true).build());
  }catch(RuntimeException e){Log.w("LUMO","Aviso visual indisponível",e);}
 }
 private static void release(){if(tone!=null){tone.release();tone=null;}if(wake!=null){if(wake.isHeld())wake.release();wake=null;}}
 private static void play(Context c){handler.post(()->{
  final long current=++generation;release();
  try{
   wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LUMO:DisconnectSound");wake.acquire(4000);
   tone=new ToneGenerator(AudioManager.STREAM_MUSIC,100);
   for(int i=0;i<3;i++)handler.postDelayed(()->{if(current==generation&&tone!=null)tone.startTone(ToneGenerator.TONE_SUP_ERROR,350);},i*550L);
   handler.postDelayed(()->{if(current==generation)release();},1800);
  }catch(RuntimeException e){release();Log.w("LUMO","Não foi possível tocar alerta",e);}
 });}
}
