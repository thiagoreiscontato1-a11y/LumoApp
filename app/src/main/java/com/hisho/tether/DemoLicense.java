package com.hisho.tether;
import android.content.*;
import java.util.Locale;
/** Offline evaluation only. Clearing app data resets the installation. */
final class DemoLicense {
 static final long DURATION=15L*24*60*60*1000;
 static boolean isDemo(Context c){return c.getPackageName().endsWith(".demo");}
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("lumo_demo",0);}
 static synchronized long now(Context c){
  SharedPreferences p=prefs(c);long wall=System.currentTimeMillis(), last=p.getLong("last",0);
  long elapsed=android.os.SystemClock.elapsedRealtime();long boot=p.getLong("boot",-1), tick=p.getLong("tick",-1);
  long currentBoot=android.provider.Settings.Global.getInt(c.getContentResolver(),"boot_count",-2);
  long effective=Math.max(wall,last);
  if(currentBoot>=0&&boot==currentBoot&&tick>=0&&elapsed>=tick)effective=Math.max(effective,last+elapsed-tick);
  p.edit().putLong("last",effective).putLong("tick",elapsed).putLong("boot",currentBoot).apply();return effective;
 }
 static long remaining(Context c){long start=prefs(c).getLong("start",0);return start==0?0:Math.max(0,DURATION-(now(c)-start));}
 static boolean allowed(Context c){return !isDemo(c)||remaining(c)>0;}
 static synchronized boolean activate(Context c,String value){
  try{String normalized=value.trim().toUpperCase(Locale.ROOT);byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(normalized.getBytes("UTF-8"));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b));
   if(!hex.toString().equals("87ace201c5447c6301155f24b390157795cb0d20c66730b8018b229dfe3e7fb6"))return false;
   if(prefs(c).getLong("start",0)>0)return allowed(c);
   return prefs(c).edit().putLong("start",now(c)).commit();
  }catch(Exception e){return false;}
 }
 static String status(Context c){long ms=remaining(c);return ms<=0?"Demonstração encerrada":"Demo · "+((ms+86399999L)/86400000L)+" dia(s) restante(s)";}
}
