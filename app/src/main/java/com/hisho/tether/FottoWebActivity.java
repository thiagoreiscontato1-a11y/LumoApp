package com.hisho.tether;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import org.json.*;
import java.util.*;

public class FottoWebActivity extends Activity {
 static final String HOME="https://fotto.alboompro.com/";
 WebView web;
 TextView title,status,counters;
 ProgressBar progress;
 final Handler handler=new Handler();
 boolean destroyed=false;
 ValueCallback<Uri[]> fileCallback;
 int lastErrors=-1;

 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 TextView tv(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",0));return t;}
 Button btn(String value){Button b=new Button(this);b.setText(value);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(0xffeaf2ff);b.setBackground(shape(0xff192331,12));b.setMinHeight(0);b.setMinimumHeight(0);return b;}

 @Override public void onCreate(Bundle saved){
  boolean dark=getSharedPreferences("hisho",0).getBoolean("darkMode",false);
  setTheme(android.R.style.Theme_Material_NoActionBar);super.onCreate(saved);
  getWindow().setStatusBarColor(dark?0xff0d1015:0xfff6f8fb);
  getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
  int bg=dark?0xff0d1015:0xfff6f8fb, card=dark?0xff151a21:Color.WHITE, text=dark?0xfff1f4f8:0xff111827, muted=dark?0xff97a2b1:0xff6b7280;

  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);

  LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(14),dp(10),dp(14),dp(10));header.setBackgroundColor(card);
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
  Button close=btn("← Lumo");close.setTextColor(0xff0b7cff);close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v->finish());
  top.addView(close,new LinearLayout.LayoutParams(dp(88),dp(42)));
  LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);
  title=tv("Fotto",17,text,true);status=tv("Abrindo painel…",11,muted,false);names.addView(title);names.addView(status);top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
  Button refresh=btn("↻");refresh.setTextSize(20);refresh.setOnClickListener(v->{if(web!=null)web.reload();});top.addView(refresh,new LinearLayout.LayoutParams(dp(46),dp(42)));
  header.addView(top);
  counters=tv("Carregados —   ·   Na fila —   ·   Erros —",12,muted,true);counters.setPadding(dp(4),dp(5),0,0);header.addView(counters);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(10);header.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
  root.addView(header);

  web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  setupWeb();

  String last=getSharedPreferences("hisho",0).getString("fottoWebUrl",HOME);
  if(last==null||last.trim().isEmpty()||!last.contains("fotto.alboompro.com"))last=HOME;
  web.loadUrl(last);
 }

 void setupWeb(){
  WebSettings s=web.getSettings();
  s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);
  s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(false);
  s.setLoadsImagesAutomatically(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
  s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);
  s.setUserAgentString(s.getUserAgentString()+" LUMO/0.14");
  CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(web,true);

  web.addJavascriptInterface(new Bridge(),"LumoFotto");
  web.setWebChromeClient(new WebChromeClient(){
   public void onProgressChanged(WebView view,int p){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}
   public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
    if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;
    try{
     Intent i=params.createIntent();i.addCategory(Intent.CATEGORY_OPENABLE);
     startActivityForResult(i,500);return true;
    }catch(Exception e){
     Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
     try{startActivityForResult(i,500);return true;}catch(Exception ignored){fileCallback=null;return false;}
    }
   }
  });
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return false;}
   @SuppressWarnings("deprecation") @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return false;}
   @Override public void onPageFinished(WebView v,String u){
    getSharedPreferences("hisho",0).edit().putString("fottoWebUrl",u).apply();
    status.setText("Painel aberto · aguardando monitoramento");
    injectMonitor();
   }
  });
 }

 @Override protected void onActivityResult(int req,int result,Intent data){
  super.onActivityResult(req,result,data);
  if(req==500&&fileCallback!=null){
   Uri[] out=null;
   if(result==RESULT_OK&&data!=null){
    if(data.getClipData()!=null){int n=data.getClipData().getItemCount();out=new Uri[n];for(int i=0;i<n;i++)out[i]=data.getClipData().getItemAt(i).getUri();}
    else if(data.getData()!=null)out=new Uri[]{data.getData()};
   }
   fileCallback.onReceiveValue(out);fileCallback=null;
  }
 }

 void injectMonitor(){
  if(web==null)return;
  String js =
   "(function(){"+
   "if(window.__lumoObserver){try{window.__lumoObserver.disconnect();}catch(e){}}"+
   "function n(s){return (s||'').replace(/\\u00a0/g,' ').replace(/\\s+/g,' ').trim();}"+
   "function pick(txt,label){var r=new RegExp(label+'\\\\s*([0-9]+)','i');var m=txt.match(r);return m?parseInt(m[1],10):-1;}"+
   "function scan(){try{var raw=document.body?document.body.innerText:'';var txt=n(raw);"+
   "var loaded=pick(txt,'CARREGADOS');var queue=pick(txt,'NA FILA');var errors=pick(txt,'COM ERRO');var ignored=pick(txt,'IGNORADOS');"+
   "var active=/Monitoramento de pasta/i.test(txt)&&(/Ativo há/i.test(txt)||/Pausar/i.test(txt)||/Parar/i.test(txt));"+
   "var paused=/Monitoramento de pasta/i.test(txt)&&/Retomar/i.test(txt);"+
   "var folder='';var fm=raw.match(/Ativo há[^\\n]*\\n\\s*([^\\n]+?)\\s*(?:\\n|Trocar)/i);if(fm)folder=n(fm[1]);"+
   "var em=location.pathname.match(/\\/events\\/([^\\/]+)\\/medias/i);var eventId=em?em[1]:'';"+
   "var rows=[];var els=document.querySelectorAll('tr');for(var i=0;i<els.length&&rows.length<20;i++){var t=n(els[i].innerText);if(t&&t.length<300)rows.push(t);}"+
   "var o={active:active,paused:paused,loaded:loaded,queue:queue,errors:errors,ignored:ignored,folder:folder,eventId:eventId,url:location.href,title:document.title,rows:rows};"+
   "LumoFotto.onState(JSON.stringify(o));"+
   "}catch(e){LumoFotto.onState(JSON.stringify({error:String(e),url:location.href}));}}"+
   "scan();window.__lumoObserver=new MutationObserver(function(){clearTimeout(window.__lumoTimer);window.__lumoTimer=setTimeout(scan,250);});"+
   "if(document.body)window.__lumoObserver.observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true});"+
   "clearInterval(window.__lumoInterval);window.__lumoInterval=setInterval(scan,1500);"+
   "})();";
  web.evaluateJavascript(js,null);
 }

 class Bridge{
  @JavascriptInterface public void onState(String raw){
   runOnUiThread(()->{
    try{
     JSONObject o=new JSONObject(raw);
     boolean active=o.optBoolean("active",false),paused=o.optBoolean("paused",false);
     int loaded=o.optInt("loaded",-1),queue=o.optInt("queue",-1),errors=o.optInt("errors",-1),ignored=o.optInt("ignored",-1);
     String folder=o.optString("folder",""),eventId=o.optString("eventId",""),url=o.optString("url",""),pageTitle=o.optString("title","");
     long now=System.currentTimeMillis();
     android.content.SharedPreferences p=getSharedPreferences("hisho",0);
     p.edit().putBoolean("fottoWebActive",active).putBoolean("fottoWebPaused",paused)
      .putInt("fottoWebLoaded",loaded).putInt("fottoWebQueue",queue).putInt("fottoWebErrors",errors).putInt("fottoWebIgnored",ignored)
      .putString("fottoWebFolder",folder).putString("fottoWebEventId",eventId).putString("fottoWebUrl",url)
      .putString("fottoWebTitle",pageTitle).putLong("fottoWebUpdatedAt",now).apply();
     if(active)status.setText("Monitoramento ativo"+(folder.isEmpty()?"":" · "+folder));
     else if(paused)status.setText("Monitoramento pausado");
     else status.setText("Abra um evento e inicie o monitoramento da pasta");
     counters.setText("Carregados "+v(loaded)+"   ·   Na fila "+v(queue)+"   ·   Erros "+v(errors)+(ignored>=0?"   ·   Ignorados "+ignored:""));
     if(errors>0&&errors!=lastErrors)EventAlert.signal(FottoWebActivity.this,"fotto_web","O Fotto registrou "+errors+" arquivo(s) com erro.");
     lastErrors=errors;
    }catch(Exception ignored){}
   });
  }
 }
 String v(int x){return x<0?"—":String.valueOf(x);}
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){destroyed=true;CookieManager.getInstance().flush();if(web!=null){web.stopLoading();web.removeJavascriptInterface("LumoFotto");web.destroy();web=null;}super.onDestroy();}
}
