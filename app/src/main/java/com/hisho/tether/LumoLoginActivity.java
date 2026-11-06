package com.hisho.tether;

import android.app.*;import android.content.*;import android.content.res.ColorStateList;import android.graphics.*;import android.graphics.drawable.*;import android.net.*;import android.os.*;import android.view.*;import android.widget.*;
import java.util.concurrent.*;

/** Tela de entrada do Lumo: sem sessão Fotto o app abre aqui; o login em si continua na FottoLoginActivity. */
public class LumoLoginActivity extends Activity {
 static final int REQUEST_FOTTO=31;
 TextView status;Button enter;ProgressBar progress;boolean destroyed=false;
 final ExecutorService net=Executors.newSingleThreadExecutor();

 @Override public void onCreate(Bundle saved){
  setTheme(LumoTheme.appTheme(this));super.onCreate(saved);
  if(LumoSession.signedIn(this)){openLumo();return;}
  getWindow().setStatusBarColor(LumoTheme.BG);getWindow().setNavigationBarColor(LumoTheme.BG);

  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(LumoTheme.BG);
  scroll.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});
  FrameLayout frame=new FrameLayout(this);scroll.addView(frame,new ScrollView.LayoutParams(-1,-1));
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(24),dp(32),dp(24),dp(28));
  // Celular: largura toda; tablet: coluna de até 440dp centralizada.
  int width=getResources().getDisplayMetrics().widthPixels>dp(500)?dp(440):-1;
  frame.addView(box,new FrameLayout.LayoutParams(width,-2,Gravity.CENTER));

  ImageView logo=new ImageView(this);int logoId=getResources().getIdentifier("ic_lumo","drawable",getPackageName());if(logoId!=0)logo.setImageResource(logoId);logo.setContentDescription("Lumo");
  box.addView(logo,new LinearLayout.LayoutParams(dp(88),dp(88)));
  TextView title=text("Lumo",34,LumoTheme.TEXT,true);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(14),0,0);box.addView(title,new LinearLayout.LayoutParams(-1,-2));
  TextView tagline=text("Captura, edição e entrega para fotógrafos",15,LumoTheme.SECONDARY,false);tagline.setGravity(Gravity.CENTER);tagline.setPadding(0,dp(4),0,dp(28));box.addView(tagline,new LinearLayout.LayoutParams(-1,-2));

  LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(20),dp(20),dp(20));card.setBackground(LumoTheme.card(LumoTheme.SURFACE,dp(20),dp(1)));
  TextView head=text("Entre com sua conta Fotto",19,LumoTheme.TEXT,true);card.addView(head);
  TextView note=text("Use o mesmo e-mail e senha do painel do Fotto. A conta identifica você no Lumo e é para onde as fotos aprovadas são enviadas.",14,LumoTheme.SECONDARY,false);note.setLineSpacing(0,1.15f);note.setPadding(0,dp(8),0,dp(18));card.addView(note);

  enter=new Button(this);enter.setText("Entrar com Fotto");enter.setAllCaps(false);enter.setTextSize(16);enter.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));enter.setTextColor(LumoTheme.ON_ACCENT);
  enter.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000),LumoTheme.gradient(dp(16)),null));enter.setStateListAnimator(null);enter.setMinHeight(dp(52));enter.setMinimumHeight(dp(52));
  Drawable person=LumoTheme.icon(this,"person",LumoTheme.ON_ACCENT,dp(20));if(person!=null){enter.setCompoundDrawablesRelative(person,null,null,null);enter.setCompoundDrawablePadding(dp(8));enter.setPadding(dp(20),0,dp(20),0);}
  enter.setOnClickListener(v->signIn());
  card.addView(enter,new LinearLayout.LayoutParams(-1,dp(54)));

  progress=new ProgressBar(this);progress.setIndeterminate(true);progress.setVisibility(View.GONE);progress.setIndeterminateTintList(ColorStateList.valueOf(LumoTheme.CYAN));
  LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(28),dp(28));pp.gravity=Gravity.CENTER_HORIZONTAL;pp.topMargin=dp(14);card.addView(progress,pp);
  status=text("",13,LumoTheme.SECONDARY,false);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(10),0,0);status.setVisibility(View.GONE);card.addView(status,new LinearLayout.LayoutParams(-1,-2));
  box.addView(card,new LinearLayout.LayoutParams(-1,-2));

  TextView offline=text("O login é feito uma vez. Depois o Lumo abre normalmente mesmo sem internet, e você pode sair da conta pelo menu.",12,LumoTheme.TERTIARY,false);
  offline.setGravity(Gravity.CENTER);offline.setLineSpacing(0,1.15f);offline.setPadding(dp(8),dp(18),dp(8),0);box.addView(offline,new LinearLayout.LayoutParams(-1,-2));
  setContentView(scroll);
  if(getIntent()!=null&&getIntent().getBooleanExtra("signedOut",false))show("Você saiu da conta. Suas fotos continuam no aparelho.",LumoTheme.SECONDARY);
 }

 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
 void show(String s,int color){status.setText(s);status.setTextColor(color);status.setVisibility(s.isEmpty()?View.GONE:View.VISIBLE);}
 void busy(boolean on){enter.setEnabled(!on);enter.setAlpha(on?.6f:1f);progress.setVisibility(on?View.VISIBLE:View.GONE);}

 boolean online(){
  try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=cm==null?null:cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Exception e){return true;}
 }

 void signIn(){
  if(!online()){show("Sem internet. Conecte-se a uma rede Wi-Fi ou aos dados móveis para entrar.",LumoTheme.WARN);return;}
  show("",LumoTheme.SECONDARY);
  try{startActivityForResult(new Intent(this,FottoLoginActivity.class),REQUEST_FOTTO);}catch(Exception e){show("Não foi possível abrir o login do Fotto: "+e.getMessage(),LumoTheme.DANGER);}
 }

 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request!=REQUEST_FOTTO)return;
  if(result!=RESULT_OK||FottoApi.token(this).isEmpty()){show("Login não concluído. Toque em Entrar com Fotto para tentar de novo.",LumoTheme.SECONDARY);return;}
  // Sessão já validada pela FottoLoginActivity (/me respondeu). Aqui só guardamos nome/e-mail para o menu.
  busy(true);show("Conta conectada. Abrindo o Lumo…",LumoTheme.OK);
  getSharedPreferences(LumoSession.PREFS,0).edit().putString("fottoLastStatus","Conta conectada. Escolha o evento na Entrega.").apply();
  net.execute(()->{try{LumoSession.rememberProfile(this,FottoApi.validateSession(this));}catch(Exception ignored){}runOnUiThread(()->{if(!destroyed)openLumo();});});
 }

 void openLumo(){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();overridePendingTransition(0,0);}

 @Override public void onBackPressed(){finishAndRemoveTask();}
 @Override protected void onDestroy(){destroyed=true;net.shutdownNow();super.onDestroy();}
}
