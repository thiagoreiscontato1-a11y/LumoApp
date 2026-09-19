package com.hisho.tether;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import org.json.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FottoWebActivity extends Activity {
 static final String HOME="https://fotto.alboompro.com/";
 static final int REQ_FILE=500,REQ_TREE=501;
 WebView web;
 TextView title,status,counters,folderStatus;
 ProgressBar progress;
 final Handler handler=new Handler();
 boolean destroyed=false;
 ValueCallback<Uri[]> fileCallback;
 int lastErrors=-1;
 Uri selectedTree;
 final Map<String,Uri> fileTokens=new ConcurrentHashMap<>();

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

  String savedTree=getSharedPreferences("hisho",0).getString("fottoTreeUri","");
  if(!savedTree.isEmpty())try{selectedTree=Uri.parse(savedTree);}catch(Exception ignored){}

  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);

  LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(14),dp(8),dp(14),dp(8));header.setBackgroundColor(card);
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
  Button close=btn("← Lumo");close.setTextColor(0xff0b7cff);close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v->finish());
  top.addView(close,new LinearLayout.LayoutParams(dp(82),dp(40)));
  LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);
  title=tv("Fotto",17,text,true);status=tv("Abrindo painel…",11,muted,false);names.addView(title);names.addView(status);top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
  Button folder=btn("Pasta");folder.setOnClickListener(v->chooseTree());
  top.addView(folder,new LinearLayout.LayoutParams(dp(72),dp(38)));
  Button refresh=btn("↻");refresh.setTextSize(20);refresh.setOnClickListener(v->{if(web!=null)web.reload();});
  LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(44),dp(38));rp.leftMargin=dp(6);top.addView(refresh,rp);
  header.addView(top);
  folderStatus=tv(folderLabel(),11,muted,false);folderStatus.setPadding(dp(4),dp(3),0,0);header.addView(folderStatus);
  counters=tv("Carregados —   ·   Na fila —   ·   Erros —",12,muted,true);counters.setPadding(dp(4),dp(3),0,0);header.addView(counters);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(10);header.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
  root.addView(header);

  web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  setupWeb();

  String last=getSharedPreferences("hisho",0).getString("fottoWebUrl",HOME);
  if(last==null||last.trim().isEmpty()||!last.contains("fotto.alboompro.com"))last=HOME;
  web.loadUrl(last);
 }

 String folderLabel(){return selectedTree==null?"Pasta do Fotto: ainda não selecionada":"Pasta do Fotto: "+treeName();}

 void setupWeb(){
  WebSettings s=web.getSettings();
  s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);
  s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(false);
  s.setLoadsImagesAutomatically(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
  s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setAllowContentAccess(true);s.setAllowFileAccess(true);
  s.setUserAgentString(s.getUserAgentString()+" LUMO/0.14.2");
  CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(web,true);

  web.addJavascriptInterface(new Bridge(),"LumoFotto");
  web.addJavascriptInterface(new FolderBridge(),"LumoFolder");

  web.setWebChromeClient(new WebChromeClient(){
   public void onProgressChanged(WebView view,int p){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}
   public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
    if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;
    try{
     Intent i=params.createIntent();i.addCategory(Intent.CATEGORY_OPENABLE);
     startActivityForResult(i,REQ_FILE);return true;
    }catch(Exception e){
     Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
     try{startActivityForResult(i,REQ_FILE);return true;}catch(Exception ignored){fileCallback=null;return false;}
    }
   }
  });

  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return false;}
   @SuppressWarnings("deprecation") @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return false;}

   @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap icon){
    injectFolderPolyfill();
   }

   @Override public void onPageFinished(WebView v,String u){
    getSharedPreferences("hisho",0).edit().putString("fottoWebUrl",u).apply();
    status.setText("Painel aberto · aguardando monitoramento");
    injectFolderPolyfill();
    injectMonitor();
   }

   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
    try{
     Uri u=request.getUrl();
     if("lumo.local".equalsIgnoreCase(u.getHost())&&u.getPath()!=null&&u.getPath().startsWith("/file/")){
      String token=u.getLastPathSegment();Uri content=fileTokens.get(token);
      if(content==null)return new WebResourceResponse("text/plain","UTF-8",404,"Not found",corsHeaders(),new java.io.ByteArrayInputStream(new byte[0]));
      String mime=getContentResolver().getType(content);if(mime==null||mime.isEmpty())mime="image/jpeg";
      InputStream in=getContentResolver().openInputStream(content);
      return new WebResourceResponse(mime,null,200,"OK",corsHeaders(),in);
     }
    }catch(Exception ignored){}
    return super.shouldInterceptRequest(view,request);
   }
  });
 }

 Map<String,String> corsHeaders(){
  Map<String,String> h=new HashMap<>();h.put("Access-Control-Allow-Origin","*");h.put("Cache-Control","no-store");return h;
 }

 void chooseTree(){
  try{
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
   i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
   if(selectedTree!=null&&Build.VERSION.SDK_INT>=26)i.putExtra("android.provider.extra.INITIAL_URI",selectedTree);
   startActivityForResult(i,REQ_TREE);
  }catch(Exception e){
   Toast.makeText(this,"Não foi possível abrir o seletor de pasta.",Toast.LENGTH_LONG).show();
  }
 }

 @Override protected void onActivityResult(int req,int result,Intent data){
  super.onActivityResult(req,result,data);

  if(req==REQ_TREE){
   if(result==RESULT_OK&&data!=null&&data.getData()!=null){
    Uri tree=data.getData();
    try{
     int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
     getContentResolver().takePersistableUriPermission(tree,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }catch(Exception ignored){}
    selectedTree=tree;
    getSharedPreferences("hisho",0).edit().putString("fottoTreeUri",tree.toString()).apply();
    folderStatus.setText(folderLabel());
    status.setText("Pasta pronta · volte ao Fotto e inicie o monitoramento");
    completeFolderPick(true);
   }else completeFolderPick(false);
   return;
  }

  if(req==REQ_FILE&&fileCallback!=null){
   Uri[] out=null;
   if(result==RESULT_OK&&data!=null){
    if(data.getClipData()!=null){int n=data.getClipData().getItemCount();out=new Uri[n];for(int i=0;i<n;i++)out[i]=data.getClipData().getItemAt(i).getUri();}
    else if(data.getData()!=null)out=new Uri[]{data.getData()};
   }
   fileCallback.onReceiveValue(out);fileCallback=null;
  }
 }

 void completeFolderPick(boolean ok){
  if(web==null)return;
  web.evaluateJavascript("try{window.__lumoCompleteFolderPick&&window.__lumoCompleteFolderPick("+(ok?"true":"false")+");}catch(e){}",null);
 }

 String treeName(){
  if(selectedTree==null)return "—";
  try{
   String id=DocumentsContract.getTreeDocumentId(selectedTree);
   int i=id.lastIndexOf(':');String n=i>=0?id.substring(i+1):id;
   if(n==null||n.trim().isEmpty())return "Selecionada";
   int slash=n.lastIndexOf('/');if(slash>=0&&slash<n.length()-1)n=n.substring(slash+1);
   return n;
  }catch(Exception e){return "Selecionada";}
 }

 JSONArray filesJson(){
  JSONArray arr=new JSONArray();
  if(selectedTree==null)return arr;
  Cursor c=null;
  try{
   String parent=DocumentsContract.getTreeDocumentId(selectedTree);
   Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(selectedTree,parent);
   String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
   c=getContentResolver().query(children,projection,null,null,null);
   if(c!=null)while(c.moveToNext()){
    String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))continue;
    long size=c.isNull(3)?0:c.getLong(3),modified=c.isNull(4)?0:c.getLong(4);
    Uri doc=DocumentsContract.buildDocumentUriUsingTree(selectedTree,id);
    String token=Integer.toHexString(doc.toString().hashCode())+"_"+Long.toHexString(size)+"_"+Long.toHexString(modified);
    fileTokens.put(token,doc);
    JSONObject o=new JSONObject();o.put("name",name==null?"foto.jpg":name);o.put("size",size);o.put("lastModified",modified);o.put("type",mime==null?"image/jpeg":mime);o.put("url","https://lumo.local/file/"+token);arr.put(o);
   }
  }catch(Exception ignored){}finally{if(c!=null)c.close();}
  return arr;
 }

 void injectFolderPolyfill(){
  if(web==null)return;
  String js=
   "(function(){"+
   "if(window.__lumoFolderPolyfill)return;window.__lumoFolderPolyfill=true;"+
   "function list(){try{return JSON.parse(LumoFolder.listFiles()||'[]');}catch(e){return [];}}"+
   "function fileHandle(m){return {kind:'file',name:m.name,queryPermission:async()=> 'granted',requestPermission:async()=> 'granted',isSameEntry:async(o)=>!!o&&o.kind==='file'&&o.name===m.name,getFile:async function(){var r=await fetch(m.url,{cache:'no-store'});if(!r.ok)throw new Error('Lumo '+r.status);var b=await r.blob();var f=new File([b],m.name,{type:m.type||b.type||'image/jpeg',lastModified:m.lastModified||Date.now()});try{Object.defineProperty(f,'webkitRelativePath',{value:(LumoFolder.folderName()||'Editadas')+'/'+m.name});}catch(e){}return f;}};}"+
   "function dir(){return {kind:'directory',name:LumoFolder.folderName()||'Editadas',queryPermission:async()=> 'granted',requestPermission:async()=> 'granted',isSameEntry:async(o)=>!!o&&o.kind==='directory'&&o.name===(LumoFolder.folderName()||'Editadas'),"+
   "values:function(){var a=list(),i=0;return {[Symbol.asyncIterator](){return this;},async next(){return i<a.length?{value:fileHandle(a[i++]),done:false}:{done:true};}};},"+
   "entries:function(){var a=list(),i=0;return {[Symbol.asyncIterator](){return this;},async next(){if(i>=a.length)return {done:true};var m=a[i++],h=fileHandle(m);return {value:[m.name,h],done:false};}};},"+
   "keys:function(){var a=list(),i=0;return {[Symbol.asyncIterator](){return this;},async next(){return i<a.length?{value:a[i++].name,done:false}:{done:true};}};},"+
   "getFileHandle:async function(name){var a=list();for(var i=0;i<a.length;i++)if(a[i].name===name)return fileHandle(a[i]);throw new DOMException('Arquivo não encontrado','NotFoundError');},"+
   "getDirectoryHandle:async function(){throw new DOMException('Subpastas não suportadas pelo Lumo','NotSupportedError');},"+
   "resolve:async function(h){return h&&h.name?[h.name]:null;}};}"+
   "var resolvePick=null,rejectPick=null;"+
   "window.__lumoCompleteFolderPick=function(ok){if(ok&&resolvePick){var r=resolvePick;resolvePick=rejectPick=null;r(dir());}else if(!ok&&rejectPick){var q=rejectPick;resolvePick=rejectPick=null;q(new DOMException('Seleção cancelada','AbortError'));}};"+
   "window.showDirectoryPicker=function(){return new Promise(function(resolve,reject){resolvePick=resolve;rejectPick=reject;LumoFolder.chooseFolder();});};"+
   "})();";
  web.evaluateJavascript(js,null);
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
   "var o={active:active,paused:paused,loaded:loaded,queue:queue,errors:errors,ignored:ignored,folder:folder,eventId:eventId,url:location.href,title:document.title};"+
   "LumoFotto.onState(JSON.stringify(o));"+
   "}catch(e){LumoFotto.onState(JSON.stringify({error:String(e),url:location.href}));}}"+
   "scan();window.__lumoObserver=new MutationObserver(function(){clearTimeout(window.__lumoTimer);window.__lumoTimer=setTimeout(scan,250);});"+
   "if(document.body)window.__lumoObserver.observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true});"+
   "clearInterval(window.__lumoInterval);window.__lumoInterval=setInterval(scan,1500);"+
   "})();";
  web.evaluateJavascript(js,null);
 }

 class FolderBridge{
  @JavascriptInterface public void chooseFolder(){runOnUiThread(()->chooseTree());}
  @JavascriptInterface public String listFiles(){return filesJson().toString();}
  @JavascriptInterface public String folderName(){return treeName();}
  @JavascriptInterface public boolean hasFolder(){return selectedTree!=null;}
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
     else status.setText(selectedTree==null?"Selecione a pasta Editadas no botão Pasta":"Pasta pronta · inicie o monitoramento no Fotto");
     counters.setText("Carregados "+v(loaded)+"   ·   Na fila "+v(queue)+"   ·   Erros "+v(errors)+(ignored>=0?"   ·   Ignorados "+ignored:""));
     if(errors>0&&errors!=lastErrors)EventAlert.signal(FottoWebActivity.this,"fotto_web","O Fotto registrou "+errors+" arquivo(s) com erro.");
     lastErrors=errors;
    }catch(Exception ignored){}
   });
  }
 }

 String v(int x){return x<0?"—":String.valueOf(x);}
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){destroyed=true;CookieManager.getInstance().flush();if(web!=null){web.stopLoading();web.removeJavascriptInterface("LumoFotto");web.removeJavascriptInterface("LumoFolder");web.destroy();web=null;}super.onDestroy();}
}
