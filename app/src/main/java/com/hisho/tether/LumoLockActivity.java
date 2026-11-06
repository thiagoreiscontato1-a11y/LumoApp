package com.hisho.tether;

import android.app.*;import android.content.*;import android.content.res.ColorStateList;import android.graphics.*;import android.graphics.drawable.*;import android.hardware.biometrics.BiometricPrompt;import android.os.*;import android.view.*;import android.widget.*;

/** Tela de bloqueio: cobre o Lumo até a digital (ou o PIN do celular) ser confirmada. Voltar não revela o app: manda para segundo plano. */
public class LumoLockActivity extends Activity {
 TextView status;CancellationSignal pending;boolean prompting=false;
 /** Pede a digital sozinho uma vez a cada volta ao app; cancelar não reabre o pedido em laço (o pedido pausa a tela em alguns aparelhos). */
 boolean autoAsked=false;

 @Override public void onCreate(Bundle saved){
  setTheme(LumoTheme.appTheme(this));super.onCreate(saved);LumoLock.showing=true;
  if(!LumoLock.enabled(this)||LumoLock.unlocked){finishQuietly();return;}
  getWindow().setStatusBarColor(LumoTheme.BG);getWindow().setNavigationBarColor(LumoTheme.BG);
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setBackgroundColor(LumoTheme.BG);box.setPadding(dp(28),dp(28),dp(28),dp(28));
  box.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(dp(28)+in.getSystemWindowInsetLeft(),dp(28)+in.getSystemWindowInsetTop(),dp(28)+in.getSystemWindowInsetRight(),dp(28)+in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});
  ImageView lock=new ImageView(this);Drawable d=LumoTheme.icon(this,"lock",LumoTheme.CYAN,dp(56));if(d!=null)lock.setImageDrawable(d);lock.setContentDescription("Bloqueado");
  box.addView(lock,new LinearLayout.LayoutParams(dp(56),dp(56)));
  TextView title=text("Lumo bloqueado",26,LumoTheme.TEXT,true);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(16),0,dp(6));box.addView(title,new LinearLayout.LayoutParams(-1,-2));
  status=text("Use sua digital para continuar. A captura e o envio seguem funcionando.",14,LumoTheme.SECONDARY,false);status.setGravity(Gravity.CENTER);status.setLineSpacing(0,1.15f);status.setPadding(0,0,0,dp(26));box.addView(status,new LinearLayout.LayoutParams(-1,-2));
  Button unlock=new Button(this);unlock.setText("Desbloquear com digital");unlock.setAllCaps(false);unlock.setTextSize(16);unlock.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));unlock.setTextColor(LumoTheme.ON_ACCENT);
  unlock.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000),LumoTheme.gradient(dp(16)),null));unlock.setStateListAnimator(null);unlock.setMinHeight(dp(52));unlock.setMinimumHeight(dp(52));unlock.setOnClickListener(v->ask());
  int width=getResources().getDisplayMetrics().widthPixels>dp(500)?dp(360):-1;
  box.addView(unlock,new LinearLayout.LayoutParams(width,dp(54)));
  Button close=new Button(this);close.setText("Fechar");close.setAllCaps(false);close.setTextSize(15);close.setTextColor(LumoTheme.CYAN);close.setBackground(null);close.setMinHeight(dp(48));close.setMinimumHeight(dp(48));close.setOnClickListener(v->moveTaskToBack(true));
  LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(width,dp(48));cp.topMargin=dp(8);box.addView(close,cp);
  setContentView(box);
 }

 @Override protected void onResume(){super.onResume();if(LumoLock.unlocked){finishQuietly();return;}if(!autoAsked){autoAsked=true;ask();}}
 @Override protected void onStop(){super.onStop();autoAsked=false;}

 void ask(){
  if(prompting||isFinishing())return;
  // Digital e PIN do celular removidos depois de ligar a trava: o aparelho está aberto, então a trava é desligada em vez de prender o usuário.
  if(!LumoLock.available(this)){LumoLock.setEnabled(this,false);Toast.makeText(this,"Bloqueio com digital desligado: o celular não tem mais digital nem bloqueio de tela.",Toast.LENGTH_LONG).show();unlocked();return;}
  prompting=true;
  pending=LumoLock.prompt(this,"Desbloquear o Lumo","Use sua digital ou o bloqueio de tela do celular",(ok,code,message)->{
   prompting=false;pending=null;
   if(ok){unlocked();return;}
   boolean cancelled=code==BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED||code==BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
   String again="Toque em Desbloquear com digital para tentar de novo.";
   status.setText(cancelled?again:(message==null?"Não foi possível confirmar.":message)+"\n"+again);
   status.setTextColor(cancelled?LumoTheme.SECONDARY:LumoTheme.WARN);
  });
 }

 void unlocked(){LumoLock.unlocked=true;finishQuietly();}
 void finishQuietly(){finish();overridePendingTransition(0,0);}
 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
 /** Voltar não pode revelar a tela de baixo. */
 @Override public void onBackPressed(){moveTaskToBack(true);}
 @Override protected void onDestroy(){LumoLock.showing=false;if(pending!=null)pending.cancel();super.onDestroy();}
}
