package com.hisho.tether;

import android.app.*;import android.content.*;import android.hardware.biometrics.*;import android.os.*;

/**
 * Bloqueio com digital: com a opção ligada, o Lumo pede a digital (ou o PIN/padrão do celular, se a digital falhar)
 * ao abrir e ao voltar depois de LOCK_AFTER fora do app. Só a tela é bloqueada: captura, edição e envio continuam.
 * Usa o BiometricPrompt do próprio Android (sem bibliotecas); a digital nunca passa pelo Lumo.
 */
final class LumoLock {
 static final String KEY="biometricLock";
 static final long LOCK_AFTER=5L*60*1000;
 /** Desbloqueado neste processo; um processo novo (app fechado pelo sistema) sempre começa bloqueado. */
 static volatile boolean unlocked=false;
 static long backgroundAt=0;
 static volatile boolean showing=false;

 static boolean enabled(Context c){return LumoSession.prefs(c).getBoolean(KEY,false);}
 static void setEnabled(Context c,boolean on){LumoSession.prefs(c).edit().putBoolean(KEY,on).apply();if(on)unlocked=true;}

 /** O app foi para segundo plano (nenhuma tela visível). */
 static void left(){backgroundAt=SystemClock.elapsedRealtime();}
 /** Voltou: passado o prazo, bloqueia de novo. */
 static void returned(){if(backgroundAt>0&&SystemClock.elapsedRealtime()-backgroundAt>=LOCK_AFTER)unlocked=false;backgroundAt=0;}

 /** Telas que nunca são bloqueadas: a própria trava e as de login/ativação (não mostram fotos). */
 static boolean exempt(Activity a){return a instanceof LumoLockActivity||a instanceof LumoLoginActivity||a instanceof FottoLoginActivity||a instanceof DemoActivationActivity;}

 static boolean needsUnlock(Activity a){return !unlocked&&!showing&&!exempt(a)&&enabled(a)&&LumoSession.signedIn(a);}

 /** Digital ou bloqueio de tela do celular disponíveis. Sem nenhum dos dois o aparelho está aberto e a trava não faz sentido. */
 static boolean available(Context c){
  try{
   BiometricManager m=c.getSystemService(BiometricManager.class);
   if(Build.VERSION.SDK_INT>=30)return m!=null&&m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK|BiometricManager.Authenticators.DEVICE_CREDENTIAL)==BiometricManager.BIOMETRIC_SUCCESS;
   KeyguardManager k=c.getSystemService(KeyguardManager.class);
   return (m!=null&&m.canAuthenticate()==BiometricManager.BIOMETRIC_SUCCESS)||(k!=null&&k.isDeviceSecure());
  }catch(Exception e){return false;}
 }

 interface Result{void done(boolean ok,int error,CharSequence message);}

 /** Abre o pedido de digital do Android. Com o PIN do celular como alternativa não há botão Cancelar próprio (o sistema oferece). */
 static CancellationSignal prompt(Activity a,String title,String subtitle,Result result){
  BiometricPrompt.Builder b=new BiometricPrompt.Builder(a).setTitle(title).setSubtitle(subtitle);
  if(Build.VERSION.SDK_INT>=30)b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK|BiometricManager.Authenticators.DEVICE_CREDENTIAL);
  else b.setDeviceCredentialAllowed(true);
  CancellationSignal cancel=new CancellationSignal();
  try{
   b.build().authenticate(cancel,a.getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){result.done(true,0,null);}
    @Override public void onAuthenticationError(int code,CharSequence message){result.done(false,code,message);}
   });
  }catch(Exception e){result.done(false,-1,e.getMessage());}
  return cancel;
 }

 private LumoLock(){}
}
