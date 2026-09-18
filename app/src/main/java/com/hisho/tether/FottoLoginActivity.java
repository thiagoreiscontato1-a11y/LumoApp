package com.hisho.tether;

import android.app.*;import android.os.*;import android.content.*;import android.graphics.Color;import android.net.Uri;import android.view.*;import android.webkit.*;import android.webkit.JavascriptInterface;import android.widget.*;import org.json.*;import java.util.*;import java.util.concurrent.*;

public class FottoLoginActivity extends Activity{
 static final String LOGIN="https://auth.alboompro.com/login?srv=fotto&redir=/&host=https://fotto.alboompro.com";
 WebView web;TextView status;Button capture;boolean delivered=false,destroyed=false;int attempts=0;volatile String seenApiKey="",seenAccessToken="";final Handler handler=new Handler(Looper.getMainLooper());final ExecutorService net=Executors.newSingleThreadExecutor();final Set<String> tested=Collections.synchronizedSet(new HashSet<String>());
 final Runnable retry=new Runnable(){public void run(){if(destroyed||delivered||web==null)return;attempts++;status.setText("Capturando sessão Fotto… tentativa "+attempts);captureUrl(web.getUrl());injectSessionReadingJs();if(!delivered&&attempts<24)handler.postDelayed(this,750);else if(!delivered)status.setText("Sessão ainda não capturada. Deixe a página do Fotto aberta e toque em ‘Usar esta sessão’. ");}};

 public void onCreate(Bundle b){super.onCreate(b);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff7f9fc);
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(16),dp(8),dp(16),dp(8));bar.setBackgroundColor(Color.WHITE);
  status=new TextView(this);status.setText("LUMO · conectar ao Fotto");status.setTextColor(0xff0b1220);status.setTextSize(14);bar.addView(status,new LinearLayout.LayoutParams(0,dp(48),1));
  Button cancel=new Button(this);cancel.setText("Fechar");cancel.setAllCaps(false);cancel.setOnClickListener(v->{setResult(RESULT_CANCELED);finish();});bar.addView(cancel,new LinearLayout.LayoutParams(dp(90),dp(48)));root.addView(bar);
  capture=new Button(this);capture.setText("Usar esta sessão");capture.setAllCaps(false);capture.setOnClickListener(v->{attempts=0;handler.removeCallbacks(retry);handler.post(retry);});root.addView(capture,new LinearLayout.LayoutParams(-1,dp(52)));
  web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);setup();
 }
 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

 void setup(){WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setJavaScriptCanOpenWindowsAutomatically(true);s.setUserAgentString(s.getUserAgentString()+" LUMO/0.9.7");
  CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(web,true);
  web.addJavascriptInterface(new Bridge(),"FottoBridge");web.setWebChromeClient(new WebChromeClient());
  web.setWebViewClient(new WebViewClient(){
   public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){captureUrl(r.getUrl().toString());return false;}
   @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView v,String u){captureUrl(u);return false;}
   public void onPageFinished(WebView v,String u){if(destroyed||delivered)return;String host="";try{host=Uri.parse(u).getHost();}catch(Exception ignored){}boolean onFotto=host!=null&&host.toLowerCase(Locale.ROOT).contains("fotto.alboompro.com");status.setText(onFotto?"Login concluído. Capturando sessão Fotto…":"Faça login para continuar");captureUrl(u);injectSessionReadingJs();if(onFotto){attempts=0;handler.removeCallbacks(retry);handler.postDelayed(retry,350);}}
  });
  web.loadUrl(LOGIN);
 }

 class Bridge{@JavascriptInterface public void onSessionReady(String raw){runOnUiThread(()->parseAndMaybeDeliver(raw));}}

 void captureUrl(String raw){if(raw==null||raw.isEmpty())return;try{Uri u=Uri.parse(raw);String host=u.getHost();if(host==null||!host.toLowerCase(Locale.ROOT).contains("fotto.alboompro.com"))return;for(String k:new String[]{"access_token","token","api_key","apiKey","at"}){String v=u.getQueryParameter(k);if(candidate(v))tryCandidate(v,"URL:"+k);}}catch(Exception ignored){}}

 void injectSessionReadingJs(){if(web==null)return;String js="(function(){try{var result={};try{var ls=window.localStorage;for(var i=0;i<ls.length;i++){var key=ls.key(i);var val=ls.getItem(key);if((val&&val.length>0&&key.toLowerCase().indexOf('token')>=0)||(val&&val.length>0&&key.toLowerCase().indexOf('api')>=0)||(val&&val.length>0&&key.toLowerCase().indexOf('key')>=0)||(val&&val.length>0&&key.toLowerCase().indexOf('session')>=0)||(val&&val.length>0&&key.toLowerCase().indexOf('auth')>=0)){result[key]=val;}}}catch(e){}try{var ss=window.sessionStorage;for(var j=0;j<ss.length;j++){var k=ss.key(j);var v=ss.getItem(k);var low=(k||'').toLowerCase();if(low.indexOf('token')>=0||low.indexOf('api')>=0||low.indexOf('auth')>=0||low.indexOf('key')>=0||low.indexOf('session')>=0){result['session_'+k]=v;}}}catch(e){}return JSON.stringify(result);}catch(e){return JSON.stringify({error:e.message});}})();";
  web.evaluateJavascript(js,val->{if(destroyed||delivered)return;parseAndMaybeDeliver(val);});
 }

 void parseAndMaybeDeliver(String value){try{Object outer=new JSONTokener(value==null?"{}":value).nextValue();String raw=outer instanceof String?(String)outer:String.valueOf(outer);if(raw.startsWith("\"")&&raw.endsWith("\"")&&raw.length()>1)raw=new JSONTokener(raw).nextValue().toString();JSONObject o=new JSONObject(raw);rememberNamedTokens(o);ArrayList<String> candidates=new ArrayList<>();collectPreferred(o,candidates);for(String v:candidates)if(candidate(v))tryCandidate(v,"storage");}catch(Exception ignored){}}

 void rememberNamedTokens(Object obj){if(obj==null||obj==JSONObject.NULL)return;if(obj instanceof JSONObject){JSONObject o=(JSONObject)obj;Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();Object v=o.opt(k);String low=k.toLowerCase(Locale.ROOT);if(v instanceof JSONObject||v instanceof JSONArray){rememberNamedTokens(v);continue;}String s=clean(String.valueOf(v));if(!candidate(s))continue;if(low.contains("access")&&low.contains("token"))seenAccessToken=s;else if((low.contains("api")&&low.contains("key"))||low.equals("apikey"))seenApiKey=s;}}else if(obj instanceof JSONArray){JSONArray a=(JSONArray)obj;for(int i=0;i<a.length();i++)rememberNamedTokens(a.opt(i));}}

 void collectPreferred(Object obj,List<String> out){if(obj==null||obj==JSONObject.NULL)return;if(obj instanceof JSONObject){JSONObject o=(JSONObject)obj;String[] priority={"api_key","apikey","apiKey","access_token","accessToken","token","session_token"};for(String p:priority){Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();if(k.equalsIgnoreCase(p)){Object v=o.opt(k);collectValue(v,out);}}}Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();Object v=o.opt(k);String low=k.toLowerCase(Locale.ROOT);if(low.contains("api")||low.contains("key")||low.contains("token")||low.contains("session")||low.contains("auth"))collectValue(v,out);else if(v instanceof JSONObject||v instanceof JSONArray)collectPreferred(v,out);}}else if(obj instanceof JSONArray){JSONArray a=(JSONArray)obj;for(int i=0;i<a.length();i++)collectPreferred(a.opt(i),out);}}
 void collectValue(Object v,List<String> out){if(v==null||v==JSONObject.NULL)return;if(v instanceof JSONObject||v instanceof JSONArray){collectPreferred(v,out);return;}String s=clean(String.valueOf(v));if(s.startsWith("{")&&s.endsWith("}")){try{collectPreferred(new JSONObject(s),out);return;}catch(Exception ignored){}}if(s.startsWith("[")&&s.endsWith("]")){try{collectPreferred(new JSONArray(s),out);return;}catch(Exception ignored){}}if(candidate(s)&&!out.contains(s))out.add(s);}

 String clean(String s){if(s==null)return "";s=s.trim();if(s.regionMatches(true,0,"Bearer ",0,7))s=s.substring(7).trim();if(s.startsWith("\"")&&s.endsWith("\"")&&s.length()>2)s=s.substring(1,s.length()-1);return s.trim();}
 boolean candidate(String s){s=clean(s);return s.length()>=16&&!s.equalsIgnoreCase("undefined")&&!s.equalsIgnoreCase("null")&&!s.startsWith("http");}

 void tryCandidate(String raw,String origin){final String key=clean(raw);if(!candidate(key)||delivered||!tested.add(key))return;status.setText("Validando sessão Fotto…");net.execute(()->{try{FottoApi.callWithToken("GET","/me",null,key);runOnUiThread(()->deliver(key,origin,false));return;}catch(Exception ignored){}try{FottoApi.callWithBearerToken("GET","/me",null,key);runOnUiThread(()->deliver(key,origin,true));}catch(Exception ignored){}});}

 void deliver(String key,String origin,boolean bearerWorked){if(delivered||destroyed)return;delivered=true;handler.removeCallbacks(retry);String token=clean(key);String api="",access="";if(!bearerWorked){api=token;access=token;}else{access=token;api=clean(seenApiKey);}getSharedPreferences("hisho",0).edit().putString("fottoApiKeyV7",api).putString("fottoAccessTokenV7",access).remove("fottoApiKeyV5").remove("fottoAccessTokenV5").remove("fottoApiKeyV4").remove("fottoAccessTokenV4").remove("fottoToken").remove("fottoTokenV2").remove("fottoTokenV3").putString("fottoLastStatus","Fotto conectado ao LUMO. Carregando eventos…").apply();status.setText("Conta conectada. Voltando ao LUMO…");Intent data=new Intent().putExtra("fottoToken",token).putExtra("fottoOrigin",origin).putExtra("fottoBearer",bearerWorked);setResult(RESULT_OK,data);handler.postDelayed(()->finish(),180);}

 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
 protected void onDestroy(){destroyed=true;handler.removeCallbacks(retry);net.shutdownNow();if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
}
