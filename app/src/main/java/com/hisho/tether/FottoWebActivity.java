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
 boolean lastActiveState=false,autoReturned=false,returningToLumo=false,keepAliveStarted=false;
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
  Button close=btn("← Lumo");close.setTextColor(0xff0b7cff);close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v->returnToLumo());
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

 String folderLabel(){return selectedTree==null?"Origem do monitoramento: ainda não selecionada":"Origem: "+treeName()+" · espelho nativo: Editadas";}

 void setupWeb(){
  WebSettings s=web.getSettings();
  s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);
  s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(false);
  s.setLoadsImagesAutomatically(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
  s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setAllowContentAccess(true);s.setAllowFileAccess(true);
  s.setUserAgentString(s.getUserAgentString()+" LUMO/0.14.5");
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
    status.setText("Pasta selecionada · preparando cópia nativa para o Fotto…");
    injectFolderPolyfill();
    handler.postDelayed(()->completeFolderPick(true),120);
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
   "try{"+
   "function log(m){try{LumoFolder.debug(String(m));}catch(e){}}"+
   "function srcList(){try{return JSON.parse(LumoFolder.listFiles()||'[]');}catch(e){log('Falha lendo Editadas: '+e);return [];}}"+
   "async function nativeDir(){"+
   " if(!navigator.storage||!navigator.storage.getDirectory)throw new Error('Este WebView não oferece OPFS/File System Access nativo');"+
   " var root=await navigator.storage.getDirectory();"+
   " return await root.getDirectoryHandle('Editadas',{create:true});"+
   "}"+
   "async function syncToNative(dir){"+
   " var files=srcList();var names={};var manifest={};"+
   " try{manifest=JSON.parse(localStorage.getItem('__lumo_opfs_manifest_v144')||'{}');}catch(e){manifest={};}"+
   " var next={};var copied=0;"+
   " for(var i=0;i<files.length;i++){"+
   "  var m=files[i];if(!m||!m.name)continue;names[m.name]=true;"+
   "  var sig=String(m.size||0)+':'+String(m.lastModified||0);next[m.name]=sig;"+
   "  if(manifest[m.name]===sig)continue;"+
   "  try{"+
   "   var r=await fetch(m.url,{cache:'no-store'});if(!r.ok)throw new Error('HTTP '+r.status);"+
   "   var b=await r.blob();"+
   "   var fh=await dir.getFileHandle(m.name,{create:true});"+
   "   var w=await fh.createWritable();await w.write(b);await w.close();copied++;"+
   "  }catch(e){log('Falha sincronizando '+m.name+': '+e);throw e;}"+
   " }"+
   " try{for await(var pair of dir.entries()){var n=pair[0];if(!names[n]){try{await dir.removeEntry(n);}catch(e){}}}}catch(e){}"+
   " try{localStorage.setItem('__lumo_opfs_manifest_v144',JSON.stringify(next));}catch(e){}"+
   " log('Pasta pronta · '+files.length+' foto(s) · '+copied+' atualizada(s)');"+
   " return dir;"+
   "}"+
   "async function prepare(){var d=await nativeDir();return await syncToNative(d);}"+
   "window.__lumoSyncFottoFolder=async function(){try{return await prepare();}catch(e){log('Não foi possível preparar a pasta: '+e);throw e;}};"+
   "var resolvePick=null,rejectPick=null;"+
   "window.__lumoCompleteFolderPick=async function(ok){"+
   " try{"+
   "  if(ok&&resolvePick){var r=resolvePick;resolvePick=rejectPick=null;var d=await prepare();log('Pasta nativa entregue ao Fotto');r(d);}"+
   "  else if(!ok&&rejectPick){var q=rejectPick;resolvePick=rejectPick=null;q(new DOMException('Seleção cancelada','AbortError'));}"+
   " }catch(e){if(rejectPick){var q2=rejectPick;resolvePick=rejectPick=null;q2(e);}log('Fotto recusou a pasta: '+e);}"+
   "};"+
   "window.showDirectoryPicker=function(){"+
   " return new Promise(async function(resolve,reject){"+
   "  try{"+
   "   if(LumoFolder.hasFolder()){var d=await prepare();log('Usando pasta Editadas pelo armazenamento nativo do WebView');resolve(d);return;}"+
   "   resolvePick=resolve;rejectPick=reject;log('Escolha a pasta Editadas');LumoFolder.chooseFolder();"+
   "  }catch(e){log('Erro preparando seletor: '+e);reject(e);}"+
   " });"+
   "};"+
   "clearInterval(window.__lumoFolderSyncInterval);"+
   "window.__lumoFolderSyncInterval=setInterval(function(){"+
   " if(!LumoFolder.hasFolder())return;"+
   " prepare().catch(function(e){log('Sincronização da pasta interrompida: '+e);});"+
   "},1800);"+
   "window.addEventListener('unhandledrejection',function(e){var r=e&&e.reason?e.reason:e;log('Fotto/arquivo: '+String(r));});"+
   "window.__lumoFolderPolyfill=true;"+
   "log('Ponte de pasta nativa pronta');"+
   "}catch(e){try{LumoFolder.debug('Falha preparando pasta: '+String(e));}catch(x){}}"+
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
   "var activeFor='';var am=raw.match(/Ativo há\\s*([^\\n]+)/i);if(am)activeFor=n(am[1]);"+
   "var folder='';var fm=raw.match(/Ativo há[^\\n]*\\n\\s*([^\\n]+?)\\s*(?:\\n|Trocar)/i);if(fm)folder=n(fm[1]);"+
   "var em=location.pathname.match(/\\/events\\/([^\\/]+)\\/medias/i);var eventId=em?em[1]:'';"+
   "var activity='';var trs=document.querySelectorAll('tr');for(var ti=trs.length-1;ti>=0;ti--){var tt=n(trs[ti].innerText);if(tt&&tt.length<220&&!/ARQUIVO\\s+STATUS/i.test(tt)){activity=tt;break;}}"+
   "var o={active:active,paused:paused,loaded:loaded,queue:queue,errors:errors,ignored:ignored,folder:folder,eventId:eventId,activeFor:activeFor,activity:activity,url:location.href,title:document.title};"+
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
  @JavascriptInterface public void debug(String msg){runOnUiThread(()->{if(msg!=null&&!msg.trim().isEmpty()){status.setText(msg);getSharedPreferences("hisho",0).edit().putString("fottoFolderDebug",msg).apply();}});}
 }

 class Bridge{
  @JavascriptInterface public void onState(String raw){
   runOnUiThread(()->{
    try{
     JSONObject o=new JSONObject(raw);
     boolean active=o.optBoolean("active",false),paused=o.optBoolean("paused",false);
     int loaded=o.optInt("loaded",-1),queue=o.optInt("queue",-1),errors=o.optInt("errors",-1),ignored=o.optInt("ignored",-1);
     String folder=o.optString("folder",""),eventId=o.optString("eventId",""),activeFor=o.optString("activeFor",""),activity=o.optString("activity",""),url=o.optString("url",""),pageTitle=o.optString("title","");
     long now=System.currentTimeMillis();
     android.content.SharedPreferences p=getSharedPreferences("hisho",0);
     p.edit().putBoolean("fottoWebActive",active).putBoolean("fottoWebPaused",paused)
      .putInt("fottoWebLoaded",loaded).putInt("fottoWebQueue",queue).putInt("fottoWebErrors",errors).putInt("fottoWebIgnored",ignored)
      .putString("fottoWebFolder",folder).putString("fottoWebEventId",eventId).putString("fottoWebUrl",url)
      .putString("fottoWebTitle",pageTitle).putString("fottoWebActiveFor",activeFor).putString("fottoWebLastActivity",activity).putLong("fottoWebUpdatedAt",now).apply();
     if(active){
      status.setText("Monitoramento ativo"+(activeFor.isEmpty()?"":" · "+activeFor)+" · pode voltar ao Lumo");
      if(!keepAliveStarted){keepAliveStarted=true;try{startForegroundService(new Intent(FottoWebActivity.this,FottoKeepAliveService.class));}catch(Exception ignored){}}
      if(!lastActiveState&&!autoReturned){
       autoReturned=true;
       handler.postDelayed(()->{if(!destroyed&&!isFinishing()){Toast.makeText(FottoWebActivity.this,"Monitoramento ativo. Voltando ao Lumo.",Toast.LENGTH_SHORT).show();returnToLumo();}},1200);
      }
     }else if(paused)status.setText("Monitoramento pausado");
     else status.setText(selectedTree==null?"Selecione a pasta Editadas no botão Pasta":"Pasta pronta · inicie o monitoramento no Fotto");
     counters.setText("Carregados "+v(loaded)+"   ·   Na fila "+v(queue)+"   ·   Erros "+v(errors)+(ignored>=0?"   ·   Ignorados "+ignored:""));
     if(errors>0&&errors!=lastErrors)EventAlert.signal(FottoWebActivity.this,"fotto_web","O Fotto registrou "+errors+" arquivo(s) com erro.");
     lastErrors=errors;lastActiveState=active;
    }catch(Exception ignored){}
   });
  }
 }

 String v(int x){return x<0?"—":String.valueOf(x);}

 void returnToLumo(){
  returningToLumo=true;
  getSharedPreferences("hisho",0).edit().putBoolean("fottoWebBackground",true).putBoolean("fottoWebHostAlive",true).apply();
  try{if(web!=null){web.resumeTimers();web.setNetworkAvailable(true);}}catch(Exception ignored){}
  Intent i=new Intent(FottoWebActivity.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  startActivity(i);
 }

 @Override protected void onResume(){
  super.onResume();
  returningToLumo=false;
  getSharedPreferences("hisho",0).edit().putBoolean("fottoWebBackground",false).putBoolean("fottoWebHostAlive",true).apply();
  try{if(web!=null){web.resumeTimers();web.setNetworkAvailable(true);}}catch(Exception ignored){}
 }

 @Override protected void onPause(){
  if(web!=null)try{web.resumeTimers();web.setNetworkAvailable(true);}catch(Exception ignored){}
  if(!isFinishing())getSharedPreferences("hisho",0).edit().putBoolean("fottoWebBackground",true).putBoolean("fottoWebHostAlive",true).apply();
  super.onPause();
 }

 @Override protected void onStop(){
  if(web!=null)try{web.resumeTimers();}catch(Exception ignored){}
  super.onStop();
 }

 @Override public void onBackPressed(){if(web!=null&&web.canGoBack()&&!getSharedPreferences("hisho",0).getBoolean("fottoWebActive",false))web.goBack();else returnToLumo();}

 @Override protected void onDestroy(){
  destroyed=true;
  getSharedPreferences("hisho",0).edit().putBoolean("fottoWebHostAlive",false).apply();
  CookieManager.getInstance().flush();
  if(web!=null){web.stopLoading();web.removeJavascriptInterface("LumoFotto");web.removeJavascriptInterface("LumoFolder");web.destroy();web=null;}
  super.onDestroy();
 }
}
