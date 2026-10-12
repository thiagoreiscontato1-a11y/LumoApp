package com.hisho.tether;
import android.app.job.*;import android.content.*;
public class SyncJobService extends JobService{
 @Override public boolean onStartJob(JobParameters params){new Thread(()->{try{QueueRecovery.resume(this);}finally{jobFinished(params,false);}},"LUMO-Retomada").start();return true;}
 @Override public boolean onStopJob(JobParameters params){return true;}
}
