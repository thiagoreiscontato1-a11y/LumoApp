package com.hisho.tether;
import android.app.*;import android.content.*;import android.os.*;import java.util.*;
public class LumoApplication extends Application implements Application.ActivityLifecycleCallbacks {
 private final Set<Activity> activities=new HashSet<>();private int started=0;private final Handler clock=new Handler(Looper.getMainLooper());
 private final Runnable check=new Runnable(){public void run(){if(DemoLicense.isDemo(LumoApplication.this)&&!DemoLicense.allowed(LumoApplication.this)){
   FottoSync.stop(LumoApplication.this);stopService(new Intent(LumoApplication.this,CaptureService.class));stopService(new Intent(LumoApplication.this,FottoKeepAliveService.class));
   boolean redirect=false;for(Activity a:new ArrayList<>(activities))if(!(a instanceof DemoActivationActivity)){redirect=true;a.finish();}
   if(redirect)startActivity(new Intent(LumoApplication.this,DemoActivationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  }if(!activities.isEmpty())clock.postDelayed(this,1000);}};
 public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
 public void onActivityCreated(Activity a,Bundle b){activities.add(a);}
 public void onActivityResumed(Activity a){clock.removeCallbacks(check);clock.post(check);}
 public void onActivityDestroyed(Activity a){activities.remove(a);if(activities.isEmpty())clock.removeCallbacks(check);}
 public void onActivityPaused(Activity a){}
 // Bloqueio com digital: conta as telas visíveis para saber quando o app saiu e voltou do segundo plano.
 public void onActivityStarted(Activity a){if(started++==0)LumoLock.returned();if(LumoLock.needsUnlock(a)){LumoLock.showing=true;a.startActivity(new Intent(a,LumoLockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));}}
 public void onActivityStopped(Activity a){if(started>0&&--started==0)LumoLock.left();}public void onActivitySaveInstanceState(Activity a,Bundle b){}
}
