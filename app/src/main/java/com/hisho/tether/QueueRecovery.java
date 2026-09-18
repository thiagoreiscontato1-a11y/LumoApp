package com.hisho.tether;
import android.content.*;import android.os.Build;import android.app.job.*;

final class QueueRecovery{
 static void schedule(Context c){try{ComponentName name=new ComponentName(c,SyncJobService.class);JobInfo info=new JobInfo.Builder(13013,name).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(15000).setOverrideDeadline(5*60*1000).build();((JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(info);}catch(Exception ignored){}}
 static void resume(Context c){
  Context app=c.getApplicationContext();Jobs j=new Jobs(app);try{
   j.retry();
   if(j.pending()>0&&CaptureService.active==null){
    Intent i=new Intent(app,CaptureService.class).putExtra("settings","{}").putExtra("resumeOnly",true);
    try{if(Build.VERSION.SDK_INT>=26)app.startForegroundService(i);else app.startService(i);}catch(Exception ignored){}
   }
  }finally{j.close();}
  try{FottoSync.reconcile(app);}catch(Exception ignored){}
  try{FottoSync.kick(app);}catch(Exception ignored){}schedule(app);
 }
 private QueueRecovery(){}
}
