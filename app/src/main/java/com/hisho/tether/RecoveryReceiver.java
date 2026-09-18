package com.hisho.tether;
import android.content.*;
public class RecoveryReceiver extends BroadcastReceiver{
 @Override public void onReceive(Context context,Intent intent){
  String a=intent==null?"":intent.getAction();
  if(Intent.ACTION_BOOT_COMPLETED.equals(a)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)||Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a))QueueRecovery.resume(context);
 }
}
