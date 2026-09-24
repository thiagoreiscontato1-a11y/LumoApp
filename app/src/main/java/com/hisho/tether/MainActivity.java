package com.hisho.tether;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
 static final String USB="com.hisho.tether.USB_PERMISSION";
 int BG,CARD,CARD2,LINE,TEXT,MUTED,GREEN,CYAN,SUCCESS,WARN,DANGER,DARK;
 boolean darkMode;

 private TextView flowStatus,badge,status,timing,previewTitle,previewSub,galleryInfo,gallerySelectionLabel,reviewHeading,fottoLiveStatus;
 private TextView folderLabel,fottoStatus,fottoEventLabel,fottoUploadStatus,fottoProblems;
 private Button connect,resume,settingsButton,themeButton,folderButton,fottoMenu,fottoSend,healthButton,eventModeButton,presetQuick;
 private CompoundButton fottoAuto;
 private boolean openEventChooserAfterLoad=false;
 private final ArrayList<FottoApi.Gallery> fottoGalleries=new ArrayList<>();
 private final ExecutorService fottoNetwork=Executors.newSingleThreadExecutor();

 private final LinearLayout[] pages=new LinearLayout[4];
 private final ScrollView[] scrollers=new ScrollView[4];
 private final Button[] tabs=new Button[4];
 private int selected=0;

 private FrameLayout previewFrame;
 private LinearLayout empty,filmStrip,reviewFiles,galleryActionBar;
 private GridLayout galleryGrid;
 private ImageView preview;
 private Bitmap previewBitmap;
 private String shownUri="",requestedUri="",gallerySignature="",filesSignature="",reviewSignature="";
 private volatile long previewGeneration=0,stripGeneration=0;
 private volatile boolean destroyed=false;
 private HorizontalScrollView filmScroll;
 private Button previousPhoto,nextPhoto,livePhoto;
 private final GallerySelection gallery=new GallerySelection();
 private final Map<String,FrameLayout> tiles=new HashMap<>();
 private final LinkedHashSet<String> editSelection=new LinkedHashSet<>();
 private boolean selectionMode=false;

 private final ExecutorService thumbnails=Executors.newFixedThreadPool(2);
 private final ExecutorService images=Executors.newSingleThreadExecutor();
 private final ExecutorService scoring=Executors.newSingleThreadExecutor();
 private final Set<String> scoringIds=Collections.synchronizedSet(new HashSet<>());
 private final android.util.LruCache<String,Bitmap> thumbCache=new android.util.LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap b){return b.getByteCount();}};
 private Jobs database;
 private JSONObject presetData=new JSONObject();
 private String presetName="Vibrant";
 private TextView report;
 private final Handler handler=new Handler();
 private boolean registered=false;
 private long lastUiRefresh=0;

 private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(USB.equals(i.getAction())){UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(d!=null&&((UsbManager)getSystemService(USB_SERVICE)).hasPermission(d))start(d);else message("Acesso USB não autorizado.");}}};

 private final Runnable update=new Runnable(){public void run(){
  if(destroyed)return;
  CaptureService s=CaptureService.active;boolean running=s!=null;
  if(connect!=null){connect.setText(!running?"Conectar câmera":s.capturing?"Encerrar captura":"Concluindo fila…");connect.setEnabled(!running||s.capturing);connect.setAlpha(connect.isEnabled()?1f:.45f);}
  if(settingsButton!=null){settingsButton.setEnabled(!running);settingsButton.setAlpha(running?.4f:1f);}
  if(resume!=null){boolean show=!running&&database.pending()>0;resume.setVisibility(show?View.VISIBLE:View.GONE);}
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);
  if(status!=null){if(running){status.setText(s.status);timing.setText(s.detail.isEmpty()?"Captura e edição em filas independentes":s.detail);report.setText(s.report());badge.setText("● "+s.connectionLabel());badge.setTextColor("CONECTADA".equals(s.connectionLabel())?SUCCESS:WARN);}else{status.setText(prefs.getString("lastStatus","Pronto para conectar a câmera."));timing.setText("Original preservado · edição e curadoria em segundo plano");report.setText(prefs.getString("lastReport","Nenhuma atividade registrada."));badge.setText("● SEM CÂMERA");badge.setTextColor(MUTED);}}
  updateGlobalStatus();
  refreshGallery();
  long now=SystemClock.elapsedRealtime();if(now-lastUiRefresh>1500){lastUiRefresh=now;if(selected==1)refreshFiles(false);if(selected==2)refreshReview(false);if(selected==3)updateFottoStatus();}
  handler.postDelayed(this,700);
 }};

 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 void applyPalette(){darkMode=getSharedPreferences("hisho",0).getBoolean("darkMode",false);if(darkMode){BG=0xff0d1015;CARD=0xff151a21;CARD2=0xff1c222b;LINE=0xff2a313b;TEXT=0xfff1f4f8;MUTED=0xff97a2b1;}else{BG=0xfff5f7fa;CARD=0xffffffff;CARD2=0xfff9fbfd;LINE=0xffe3e8ef;TEXT=0xff101722;MUTED=0xff6f7d91;}GREEN=0xff0b7cff;CYAN=0xff22dff3;SUCCESS=0xff20b46a;WARN=0xffff9f43;DANGER=0xffe45555;DARK=0xff101820;}
 GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 GradientDrawable border(int color,int radius){GradientDrawable d=shape(color,radius);d.setStroke(dp(1),LINE);return d;}
 GradientDrawable selectedBorder(int color,int radius){GradientDrawable d=shape(color,radius);d.setStroke(dp(2),GREEN);return d;}
 LinearLayout vertical(){LinearLayout l=new LinearLayout(MainActivity.this);l.setOrientation(LinearLayout.VERTICAL);return l;}
 LinearLayout row(){LinearLayout l=new LinearLayout(MainActivity.this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
 TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(MainActivity.this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return t;}
 void gap(LinearLayout l,int height){l.addView(new View(MainActivity.this),new LinearLayout.LayoutParams(1,dp(height)));}
 TextView label(LinearLayout p,String value,int size){TextView t=text(value,size,MUTED,false);t.setLineSpacing(dp(2),1);p.addView(t);return t;}
 void heading(LinearLayout p,String title,String subtitle){LinearLayout r=row();TextView h=text(title,24,TEXT,true);r.addView(h,new LinearLayout.LayoutParams(0,-2,1));p.addView(r);if(subtitle!=null&&!subtitle.isEmpty()){gap(p,3);label(p,subtitle,12);}gap(p,14);}
 LinearLayout panel(LinearLayout p){LinearLayout c=vertical();c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(border(CARD,16));p.addView(c,new LinearLayout.LayoutParams(-1,-2));return c;}
 Button compactButton(String value,View.OnClickListener listener){Button b=new Button(MainActivity.this);b.setText(value);b.setTextSize(13);b.setTextColor(TEXT);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x180b7cff),border(CARD2,12),null));b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),0,dp(10),0);b.setOnClickListener(listener);return b;}
 void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(GREEN,12),null));}
 TextView chip(String value,int fg,int bg){TextView t=text(value,10,fg,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(7),dp(4),dp(7),dp(4));t.setBackground(shape(bg,10));return t;}

 @Override public void onCreate(Bundle saved){
  darkMode=getSharedPreferences("hisho",0).getBoolean("darkMode",false);if(getSharedPreferences("hisho",0).getBoolean("eventMode",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setTheme(darkMode?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);applyPalette();database=new Jobs(MainActivity.this);loadPreset();PresetLibrary.seed(MainActivity.this,presetName,presetData);QueueRecovery.resume(MainActivity.this);
  if(saved!=null){gallery.follow=saved.getBoolean("follow",true);gallery.selected=saved.getString("photo","");}
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(CARD);getWindow().getDecorView().setSystemUiVisibility(darkMode?0:(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
  LinearLayout root=vertical();root.setBackgroundColor(BG);root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});setContentView(root);

  LinearLayout header=vertical();header.setPadding(dp(16),dp(8),dp(16),dp(8));header.setBackgroundColor(CARD);
  LinearLayout top=row();LumoMark logo=new LumoMark(MainActivity.this);top.addView(logo,new LinearLayout.LayoutParams(dp(34),dp(34)));TextView brand=text("LUMO",19,TEXT,true);brand.setPadding(dp(9),0,0,0);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
  healthButton=compactButton("Saúde",v->showHealthPanel());top.addView(healthButton,new LinearLayout.LayoutParams(dp(68),dp(38)));themeButton=compactButton(darkMode?"☀":"☾",v->{getSharedPreferences("hisho",0).edit().putBoolean("darkMode",!darkMode).apply();recreate();});LinearLayout.LayoutParams thp=new LinearLayout.LayoutParams(dp(44),dp(38));thp.leftMargin=dp(5);top.addView(themeButton,thp);settingsButton=compactButton("⚙",v->captureSettingsDialog());LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(44),dp(38));sp.leftMargin=dp(5);top.addView(settingsButton,sp);header.addView(top);
  flowStatus=text("Canon • Sem câmera
Bateria · armazenamento
Pendentes · processadas · enviadas",10,MUTED,true);flowStatus.setSingleLine(false);flowStatus.setMaxLines(3);flowStatus.setHorizontallyScrolling(false);flowStatus.setEllipsize(null);flowStatus.setLineSpacing(dp(2),1f);flowStatus.setIncludeFontPadding(false);LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(-1,-2);fl.topMargin=dp(4);fl.bottomMargin=dp(2);header.addView(flowStatus,fl);root.addView(header);

  FrameLayout content=new FrameLayout(MainActivity.this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
  for(int i=0;i<4;i++){scrollers[i]=new ScrollView(MainActivity.this);scrollers[i].setFillViewport(true);scrollers[i].setVerticalScrollBarEnabled(false);pages[i]=vertical();pages[i].setPadding(dp(14),dp(12),dp(14),dp(18));scrollers[i].addView(pages[i]);content.addView(scrollers[i],new FrameLayout.LayoutParams(-1,-1));}
  capturePage();galleryPage();reviewPage();fottoPage();

  LinearLayout nav=row();nav.setPadding(dp(8),dp(7),dp(8),dp(7));nav.setBackgroundColor(CARD);String[] names={"Captura","Galeria","Revisão","Entrega"};for(int i=0;i<4;i++){final int page=i;Button t=compactButton(names[i],v->select(page));t.setTextSize(12);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);lp.setMargins(dp(2),0,dp(2),0);nav.addView(t,lp);tabs[i]=t;}root.addView(nav);select(saved==null?0:saved.getInt("page",0));

  report=new TextView(MainActivity.this);
  IntentFilter filter=new IntentFilter(USB);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,filter);registered=true;
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},20);
  if(getSharedPreferences("hisho",0).getBoolean("eventMode",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);handler.post(update);
 }

 void loadPreset(){try{String saved=getSharedPreferences("hisho",0).getString("preset",null);presetData=new JSONObject(saved==null?read(getAssets().open("vibrant.json")):saved);presetName=getSharedPreferences("hisho",0).getString("presetName","Vibrant");}catch(Exception e){presetData=new JSONObject();presetName="Vibrant";}}

 JSONObject buildCurrentSettings()throws Exception{
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);JSONObject cfg=new JSONObject();cfg.put("auto",p.getBoolean("autoEnabled",true));cfg.put("autoStrength",WorkProfiles.autoStrength(MainActivity.this));int res=Math.max(0,Math.min(2,p.getInt("resolution",0)));cfg.put("edge",res==0?2560:res==1?1920:0);cfg.put("curation",p.getBoolean("curationEnabled",true));cfg.put("curationSensitivity",Math.max(0,Math.min(2,p.getInt("curationSensitivity",1))));cfg.put("backfill",p.getBoolean("backfillEnabled",true));cfg.put("presetName",p.getString("presetName",presetName));if(p.getBoolean("presetEnabled",true))cfg.put("preset",presetData);return cfg;
 }
 void publishLiveSettings(){try{loadPreset();JSONObject cfg=buildCurrentSettings();getSharedPreferences("hisho",0).edit().putString("liveSettings",cfg.toString()).apply();}catch(Exception ignored){}}
 void refreshQuickControls(){if(presetQuick!=null){boolean enabled=getSharedPreferences("hisho",0).getBoolean("presetEnabled",true);presetQuick.setText(enabled?"Predefinição: "+presetName:"Predefinição: desligada");}if(eventModeButton!=null){boolean on=getSharedPreferences("hisho",0).getBoolean("eventMode",false);eventModeButton.setText(on?"Evento ativo":"Modo evento");eventModeButton.setTextColor(on?SUCCESS:TEXT);if(settingsButton!=null)settingsButton.setVisibility(on?View.GONE:View.VISIBLE);}}
 void toggleEventMode(){
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);boolean on=!p.getBoolean("eventMode",false);android.content.SharedPreferences.Editor e=p.edit().putBoolean("eventMode",on);
  if(on){e.putBoolean("backfillEnabled",true).putBoolean("curationEnabled",true).putBoolean("disconnectSound",true);if(!FottoApi.token(MainActivity.this).isEmpty()&&!p.getString("fottoGalleryId","").isEmpty())e.putBoolean("fottoAuto",true);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
  else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  e.apply();publishLiveSettings();refreshQuickControls();if(on){QueueRecovery.resume(MainActivity.this);FottoSync.kick(MainActivity.this);message("Modo evento ativado.");}else message("Modo evento desativado.");
 }
 void showPresetChooser(){
  ArrayList<String> names=PresetLibrary.names(MainActivity.this);ArrayList<String> items=new ArrayList<>();items.add("Sem predefinição");items.addAll(names);items.add("Importar nova predefinição XMP…");
  new AlertDialog.Builder(MainActivity.this).setTitle("Predefinição durante o evento").setItems(items.toArray(new String[0]),(d,n)->{
   if(n==0){getSharedPreferences("hisho",0).edit().putBoolean("presetEnabled",false).apply();publishLiveSettings();refreshQuickControls();message("Predefinição desligada para as próximas fotos.");return;}
   if(n==items.size()-1){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),10);return;}
   String name=items.get(n);JSONObject chosen=PresetLibrary.get(MainActivity.this,name);presetData=chosen;presetName=name;getSharedPreferences("hisho",0).edit().putString("preset",chosen.toString()).putString("presetName",name).putBoolean("presetEnabled",true).apply();publishLiveSettings();refreshQuickControls();message("Predefinição "+name+" aplicada às próximas fotos.");
  }).show();
 }
 String age(long at){if(at<=0)return "—";long s=Math.max(0,(System.currentTimeMillis()-at)/1000);if(s<60)return "há "+s+"s";if(s<3600)return "há "+(s/60)+"min";return "há "+(s/3600)+"h";}
 void showHealthPanel(){
  CaptureService cs=CaptureService.active;android.content.SharedPreferences p=getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId","");int errors=0;try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM jobs WHERE state IN ('error','originalError')",null)){if(c.moveToFirst())errors=c.getInt(0);}int q=database.pending(),reviews=database.reviewCount(),fottoPending=gallery.isEmpty()?0:database.fottoPending(gallery),confirmed=gallery.isEmpty()?0:database.fottoDone(gallery);String lastFotto=p.getString("fottoLastStatus","Sem atividade");
  LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(12));
  String camera=cs!=null&&cs.capturing?"OK · "+cs.cameraName:"Sem câmera ativa";String download=errors==0?"OK":"Atenção · "+errors+" erro(s)";String edit=q==0?"OK":"Processando · "+q;String cur=reviews==0?"OK":"Revisão · "+reviews;String fotto=FottoApi.token(MainActivity.this).isEmpty()?"Não conectado":(gallery.isEmpty()?"Sem evento":(database.fottoLastError(gallery).isEmpty()?"OK":"Atenção"));
  String[] lines={"Câmera: "+camera,"Recebimento: "+download,"Edição: "+edit,"Curadoria: "+cur,"Fotto direto: "+fotto,"Fila: "+q,"Última foto recebida: "+age(Math.max(database.lastDownloadedAt(),p.getLong("lastPhotoAt",0))),"Fotto: "+confirmed+" confirmada(s) · "+fottoPending+" pendente(s)","Status Fotto: "+lastFotto};
  for(String line:lines){TextView t=text(line,13,line.contains("Atenção")||line.contains("Não conectado")?WARN:TEXT,line.endsWith("OK"));box.addView(t,new LinearLayout.LayoutParams(-1,dp(31)));}
  new AlertDialog.Builder(MainActivity.this).setTitle("Saúde do fluxo").setView(box).setPositiveButton("Fechar",null).setNeutralButton("Retomar filas",(d,n)->QueueRecovery.resume(MainActivity.this)).show();
 }

 void showPhotoDetails(PhotoItem item){
  if(item==null)return;String[] meta=database.photoMeta(item.id);LinearLayout box=vertical();box.setPadding(dp(18),dp(8),dp(18),dp(8));ImageView im=new ImageView(MainActivity.this);im.setScaleType(ImageView.ScaleType.CENTER_CROP);box.addView(im,new LinearLayout.LayoutParams(-1,dp(180)));loadThumb(im,item.uri,600);gap(box,8);
  TextView score=text("Nota "+item.score+"/10",17,item.score>=8?SUCCESS:item.score>=5?WARN:DANGER,true);box.addView(score);gap(box,4);
  String note=database.qualityNote(item.id);if(!note.isEmpty())label(box,note,12);String semantic=database.semanticNote(item.id);if(!semantic.isEmpty()){gap(box,5);label(box,"Curadoria inteligente: "+semantic,12);}
  String[] burst=database.burstSummary(item.id);if(burst.length>1){StringBuilder b=new StringBuilder("Rajada · ");for(int i=0;i<Math.min(5,burst.length);i++){String[] x=burst[i].split("\\|",-1);if(i==0)b.append("melhor ");else b.append(" · ");b.append(x.length>2?x[2]:"?").append("/10");}gap(box,5);label(box,b.toString(),12);}
  gap(box,5);label(box,"Fotto: o status individual agora é conferido no painel web do evento.",12);
  gap(box,8);box.addView(text("Histórico",14,TEXT,true));java.text.SimpleDateFormat tf=new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT);for(String[] h:database.historyRows(item.id)){long ts=0;try{ts=Long.parseLong(h[0]);}catch(Exception ignored){}label(box,tf.format(new Date(ts))+" · "+h[1]+(h[2].isEmpty()?"":" · "+h[2]),11);}
  ScrollView scroll=new ScrollView(MainActivity.this);scroll.addView(box);new AlertDialog.Builder(MainActivity.this).setTitle(item.name.replaceFirst("(?i)\\.jpg$","")).setView(scroll).setPositiveButton("Abrir foto",(d,n)->openImage(item.uri)).setNeutralButton("Editar",(d,n)->openEditor(item.id,Collections.singletonList(item.id),false)).setNegativeButton("Fechar",null).show();
 }
 void select(int page){if(page<0||page>=4)page=0;selected=page;for(int i=0;i<4;i++){scrollers[i].setVisibility(i==page?View.VISIBLE:View.GONE);tabs[i].setTextColor(i==page?GREEN:MUTED);tabs[i].setBackground(i==page?shape(darkMode?0xff152840:0xffeaf4ff,12):shape(Color.TRANSPARENT,12));}if(page==1)refreshFiles(true);if(page==2)refreshReview(true);if(page==3)updateFottoStatus();}

 void capturePage(){
  LinearLayout p=pages[0];
  LinearLayout top=row();badge=chip("● SEM CÂMERA",MUTED,darkMode?0xff20262e:0xffedf1f6);top.addView(badge);top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));connect=compactButton("Conectar câmera",v->{CaptureService s=CaptureService.active;if(s!=null){if(s.capturing)new AlertDialog.Builder(MainActivity.this).setTitle("Encerrar captura?").setMessage("A foto em andamento e as edições pendentes serão concluídas.").setPositiveButton("Encerrar",(d,n)->startService(new Intent(MainActivity.this,CaptureService.class).setAction("stop"))).setNegativeButton("Continuar",null).show();}else chooseTransport();});primary(connect);top.addView(connect,new LinearLayout.LayoutParams(dp(148),dp(42)));p.addView(top);gap(p,7);
  LinearLayout quick=row();presetQuick=compactButton("Predefinição: "+presetName,v->showPresetChooser());quick.addView(presetQuick,new LinearLayout.LayoutParams(0,dp(38),1));eventModeButton=compactButton("Modo evento",v->toggleEventMode());LinearLayout.LayoutParams em=new LinearLayout.LayoutParams(dp(112),dp(38));em.leftMargin=dp(6);quick.addView(eventModeButton,em);p.addView(quick);refreshQuickControls();gap(p,9);

  int screen=getResources().getConfiguration().screenHeightDp;int previewH=Math.max(390,Math.min(680,(int)(screen*.66f)));
  previewFrame=new FrameLayout(MainActivity.this);previewFrame.setBackground(shape(darkMode?0xff090b0f:0xff111318,18));previewFrame.setClipToOutline(true);p.addView(previewFrame,new LinearLayout.LayoutParams(-1,dp(previewH)));
  preview=new ImageView(MainActivity.this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("Última foto. Toque para ampliar.");FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(-1,-1);ip.bottomMargin=dp(50);previewFrame.addView(preview,ip);preview.setOnClickListener(v->fullscreen());
  empty=vertical();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(18),dp(18),dp(18),dp(18));previewFrame.addView(empty,new FrameLayout.LayoutParams(-1,-1));View art=new CameraArt(MainActivity.this);empty.addView(art,new LinearLayout.LayoutParams(dp(96),dp(86)));gap(empty,10);TextView wait=text("Aguardando a próxima foto",16,Color.WHITE,true);empty.addView(wait);gap(empty,5);TextView note=text("Conecte a Canon e fotografe normalmente.",12,0xffaab4c3,false);empty.addView(note);
  LinearLayout footer=vertical();footer.setPadding(dp(13),dp(8),dp(13),dp(8));footer.setBackgroundColor(0xd9000000);FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-1,dp(50),Gravity.BOTTOM);previewFrame.addView(footer,fp);previewTitle=text("AO VIVO",9,CYAN,true);footer.addView(previewTitle);previewSub=text("Original preservado · aguardando",11,Color.WHITE,false);footer.addView(previewSub);

  gap(p,8);filmScroll=new HorizontalScrollView(MainActivity.this);filmScroll.setHorizontalScrollBarEnabled(false);filmStrip=row();filmScroll.addView(filmStrip);p.addView(filmScroll,new LinearLayout.LayoutParams(-1,dp(76)));
  LinearLayout filmNav=row();previousPhoto=miniNav("‹",v->movePhoto(-1));filmNav.addView(previousPhoto,new LinearLayout.LayoutParams(dp(38),dp(34)));livePhoto=miniNav("● Novas",v->{gallery.followLatest();showSelection(true);});LinearLayout.LayoutParams liveLp=new LinearLayout.LayoutParams(0,dp(34),1);liveLp.setMargins(dp(5),0,dp(5),0);filmNav.addView(livePhoto,liveLp);nextPhoto=miniNav("›",v->movePhoto(1));filmNav.addView(nextPhoto,new LinearLayout.LayoutParams(dp(38),dp(34)));p.addView(filmNav);galleryInfo=label(p,"As fotos recentes aparecem aqui.",11);
  gap(p,7);fottoLiveStatus=text("Fotto direto • configure em Entrega",11,MUTED,true);fottoLiveStatus.setPadding(dp(10),dp(8),dp(10),dp(8));fottoLiveStatus.setBackground(border(CARD2,12));fottoLiveStatus.setMaxLines(3);p.addView(fottoLiveStatus);

  gap(p,8);status=text("Pronto para conectar.",12,TEXT,true);p.addView(status);timing=label(p,"Original preservado · edição e curadoria em segundo plano",11);
  resume=compactButton("Retomar edições pendentes",v->start(null));resume.setVisibility(View.GONE);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(42));rp.topMargin=dp(7);p.addView(resume,rp);
 }
 Button miniNav(String title,View.OnClickListener l){Button b=compactButton(title,l);b.setTextSize(11);return b;}

 void galleryPage(){
  LinearLayout p=pages[1];heading(p,"Galeria","Grade rápida para revisar grandes volumes. Segure uma foto para selecionar várias.");
  gallerySelectionLabel=text("Toque abre · segure para selecionar",11,MUTED,false);p.addView(gallerySelectionLabel);gap(p,8);
  galleryActionBar=row();galleryActionBar.setPadding(dp(4),dp(4),dp(4),dp(4));galleryActionBar.setBackground(border(CARD,14));String[] actions={"Editar","Copiar","Colar","Aprovar","Fotto"};for(String a:actions){Button b=miniNav(a,v->{String x=((Button)v).getText().toString();if("Editar".equals(x))editSelected();else if("Copiar".equals(x))copyGalleryAdjustments();else if("Colar".equals(x))pasteGalleryAdjustments();else if("Aprovar".equals(x))approveSelected();else openFottoWeb();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(dp(2),0,dp(2),0);galleryActionBar.addView(b,lp);}galleryActionBar.setVisibility(View.GONE);p.addView(galleryActionBar);gap(p,9);
  galleryGrid=new GridLayout(MainActivity.this);galleryGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);p.addView(galleryGrid,new LinearLayout.LayoutParams(-1,-2));gap(p,10);
  Button clear=compactButton("Limpar seleção",v->{editSelection.clear();selectionMode=false;filesSignature="";refreshFiles(true);});p.addView(clear,new LinearLayout.LayoutParams(-1,dp(42)));
 }

 void reviewPage(){
  LinearLayout p=pages[2];reviewHeading=text("Revisão (0)",24,TEXT,true);p.addView(reviewHeading);gap(p,3);label(p,"Somente fotos que a curadoria sinalizou. Nota técnica de 1 a 10; aprovação manual libera o envio.",12);gap(p,12);reviewFiles=vertical();p.addView(reviewFiles);
 }

 void fottoPage(){
  LinearLayout p=pages[3];heading(p,"Entrega","Upload direto para o Fotto: o Lumo cria a mídia, envia a foto ao S3 e confirma o processamento no evento.");
  LinearLayout sync=panel(p);
  LinearLayout head=row();fottoStatus=text("Fotto • Não conectado",18,TEXT,true);head.addView(fottoStatus,new LinearLayout.LayoutParams(0,-2,1));
  fottoMenu=compactButton("⋯",v->deliverMenu());head.addView(fottoMenu,new LinearLayout.LayoutParams(dp(48),dp(40)));sync.addView(head);
  gap(sync,5);fottoEventLabel=label(sync,"Evento: —",13);gap(sync,4);fottoUploadStatus=label(sync,"0 confirmadas · 0 na fila",13);gap(sync,10);
  LinearLayout monitor=row();LinearLayout copy=vertical();copy.addView(text("Envio direto automático",14,TEXT,true));copy.addView(text("Nova foto aprovada → mídia Fotto → S3 → confirmação",11,MUTED,false));monitor.addView(copy,new LinearLayout.LayoutParams(0,-2,1));Switch sw=new Switch(MainActivity.this);fottoAuto=sw;sw.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{GREEN,0xff8d98a8}));monitor.addView(sw);sync.addView(monitor);
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);sw.setChecked(prefs.getBoolean("fottoAuto",false));bindFottoToggle();
  gap(sync,9);fottoSend=compactButton("Enviar pendentes agora",v->{if(!checkFottoReady())return;FottoSync.sendExisting(MainActivity.this);prefs.edit().putString("fottoLastStatus","Procurando Editadas pendentes…").apply();updateFottoStatus();});primary(fottoSend);sync.addView(fottoSend,new LinearLayout.LayoutParams(-1,dp(42)));
  gap(sync,7);Button stopSend=compactButton("Encerrar envio",v->{FottoSync.stop(MainActivity.this);if(fottoAuto!=null){fottoAuto.setOnCheckedChangeListener(null);fottoAuto.setChecked(false);bindFottoToggle();}updateFottoStatus();message(FottoSync.running()?"Envio será encerrado assim que a foto atual terminar.":"Envio encerrado.");});stopSend.setTextColor(DANGER);sync.addView(stopSend,new LinearLayout.LayoutParams(-1,dp(42)));
  gap(sync,7);Button web=compactButton("Abrir Fotto Web · fallback",v->openFottoWeb());sync.addView(web,new LinearLayout.LayoutParams(-1,dp(42)));
  gap(sync,5);label(sync,"O monitoramento de pasta não é necessário para o upload direto. Use o Fotto Web apenas para conferir o evento ou como fallback.",11);
  gap(p,10);fottoProblems=text("",12,DANGER,false);fottoProblems.setPadding(dp(12),dp(10),dp(12),dp(10));fottoProblems.setBackground(border(darkMode?0xff2a1717:0xfffff6f6,12));fottoProblems.setVisibility(View.GONE);p.addView(fottoProblems);
  gap(p,10);LinearLayout storage=panel(p);LinearLayout sr=row();LinearLayout st=vertical();st.addView(text("Pasta produzida pelo Lumo",14,TEXT,true));folderLabel=text("",12,MUTED,false);st.addView(folderLabel);sr.addView(st,new LinearLayout.LayoutParams(0,-2,1));folderButton=compactButton("Alterar",v->chooseExportFolder());sr.addView(folderButton,new LinearLayout.LayoutParams(dp(92),dp(38)));storage.addView(sr);
  gap(storage,8);LinearLayout cleanRow=row();Button cleanEdited=compactButton("Limpar pasta Editadas",v->confirmClearEdited());cleanEdited.setTextColor(DANGER);cleanRow.addView(cleanEdited,new LinearLayout.LayoutParams(-1,dp(42)));storage.addView(cleanRow);
  gap(storage,4);label(storage,"Remove somente as cópias editadas. Originais, notas, histórico e fotos em Revisão são preservados.",11);updateExportFolder();
  updateFottoStatus();if(!FottoApi.token(MainActivity.this).isEmpty())loadFottoGalleries();FottoSync.kick(MainActivity.this);
 }

 void openFottoWeb(){Intent i=new Intent(MainActivity.this,FottoWebActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);}

 boolean compactHeader(){return getResources().getConfiguration().screenWidthDp<430||getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT;}

 void updateGlobalStatus(){
  if(flowStatus==null)return;CaptureService c=CaptureService.active;android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);
  String cam=c==null?"Canon":c.cameraName,conn=c==null?"Sem câmera":c.connectionLabel();int battery=-1;try{battery=((BatteryManager)getSystemService(BATTERY_SERVICE)).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);}catch(Exception ignored){}
  long freeBytes=Math.max(0,getFilesDir().getUsableSpace()),gb=freeBytes/(1024L*1024L*1024L);int transfer=c==null?prefs.getInt("lastReceived",0):c.received,editing=database.processingCount(),processed=database.doneCount();
  String gallery=prefs.getString("fottoGalleryId","");int confirmed=gallery.isEmpty()?prefs.getInt("fottoUploadedCount",0):database.fottoDone(gallery),sent=gallery.isEmpty()?confirmed:database.fottoAccepted(gallery),pending=gallery.isEmpty()?0:database.fottoPending(gallery),processing=gallery.isEmpty()?0:database.fottoProcessing(gallery);boolean directActive=prefs.getBoolean("fottoAuto",false);String fErr=gallery.isEmpty()?"":database.fottoLastError(gallery);
  String b=battery<0?"—":battery+"%";boolean lowBattery=battery>=0&&battery<=20,lowSpace=freeBytes>0&&freeBytes<1024L*1024L*1024L;
  if(compactHeader()){
   String line1=cam+" • "+conn;
   String line2="bateria "+b+" · "+gb+" GB";
   String line3="↓ "+transfer+" novas · pend. "+editing+" · proc. "+processed+" · ↑ "+sent;
   if(!gallery.isEmpty())line3+="
Fotto: fila "+pending+" · enviando "+processing;
   flowStatus.setText(line1+"
"+line2+"
"+line3);
  }else{
   flowStatus.setText(cam+" • "+conn+"   |   bateria "+b+" · "+gb+" GB   |   ↓ "+transfer+"   ✦ "+editing+"   ✓ "+processed+"   ↑ "+sent+(gallery.isEmpty()?"":"   |   fila "+pending+" · env. "+processing));
  }
  flowStatus.setTextColor(lowBattery||lowSpace||!fErr.isEmpty()?WARN:MUTED);
  if(fottoLiveStatus!=null){String state=FottoApi.token(MainActivity.this).isEmpty()?"NÃO CONECTADO":gallery.isEmpty()?"SEM EVENTO":directActive?"ATIVO":"PAUSADO";if(FottoSync.running())state="ENVIANDO";String title=prefs.getString("fottoGalleryTitle","");String line1="Fotto direto • "+state+(title.isEmpty()?"":" · "+title);String line2="Enviadas "+sent+" · confirmadas "+confirmed+" · processando "+processing;String last=prefs.getString("fottoLastStatus","");if(!last.isEmpty()&&last.length()<150)line2+="\n"+last;fottoLiveStatus.setText(line1+"\n"+line2);fottoLiveStatus.setTextColor(!fErr.isEmpty()?WARN:(directActive?SUCCESS:MUTED));}
  if(prefs.getBoolean("eventMode",false)){if(lowBattery)EventAlert.signal(MainActivity.this,"bateria","Bateria em "+battery+"%. Conecte o carregador.");if(lowSpace)EventAlert.signal(MainActivity.this,"espaco","Menos de 1 GB livre. Libere armazenamento.");if(!fErr.isEmpty())EventAlert.signal(MainActivity.this,"fotto","Falha na entrega Fotto: "+fErr);}
  int reviews=database.reviewCount();if(tabs[2]!=null)tabs[2].setText(reviews>0?"Revisão ("+reviews+")":"Revisão");
 }

 void captureSettingsDialog(){
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);ScrollView scroll=new ScrollView(MainActivity.this);LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(12));scroll.addView(box);

  box.addView(text("Perfil de trabalho",13,TEXT,true));Spinner profile=new Spinner(MainActivity.this);profile.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,WorkProfiles.NAMES));String active=prefs.getString("workProfile","Personalizado");int activeIndex=0;for(int i=0;i<WorkProfiles.NAMES.length;i++)if(WorkProfiles.NAMES[i].equals(active))activeIndex=i;profile.setSelection(activeIndex);final boolean[] firstProfile={true};profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(firstProfile[0]){firstProfile[0]=false;return;}String name=WorkProfiles.NAMES[pos];WorkProfiles.apply(MainActivity.this,name);loadPreset();publishLiveSettings();refreshQuickControls();message("Perfil "+name+" aplicado.");}public void onNothingSelected(android.widget.AdapterView<?> p){}});box.addView(profile,new LinearLayout.LayoutParams(-1,dp(48)));

  TextView pn=text("Predefinição · "+presetName,15,TEXT,true);box.addView(pn);gap(box,8);
  Button choosePreset=compactButton("Trocar predefinição agora",v->showPresetChooser());box.addView(choosePreset,new LinearLayout.LayoutParams(-1,dp(42)));gap(box,5);

  Switch auto=settingSwitch(box,"Correção automática",prefs.getBoolean("autoEnabled",true));auto.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("autoEnabled",c).apply();publishLiveSettings();});
  box.addView(text("Intensidade da correção automática",13,TEXT,true));SeekBar strength=new SeekBar(MainActivity.this);strength.setMax(150);strength.setProgress((int)Math.round(WorkProfiles.autoStrength(MainActivity.this)*100));TextView strengthLabel=text(String.format(Locale.ROOT,"%d%%",strength.getProgress()),11,MUTED,false);box.addView(strengthLabel);strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){strengthLabel.setText(value+"%");prefs.edit().putLong("autoStrengthBits",Double.doubleToLongBits(value/100.0)).apply();if(user)publishLiveSettings();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){publishLiveSettings();}});box.addView(strength,new LinearLayout.LayoutParams(-1,dp(40)));

  Switch preset=settingSwitch(box,"Aplicar predefinição",prefs.getBoolean("presetEnabled",true));preset.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("presetEnabled",c).apply();publishLiveSettings();refreshQuickControls();});
  gap(box,8);box.addView(text("Resolução da cópia editada",13,TEXT,true));Spinner resolution=new Spinner(MainActivity.this);String[] modes={"Rápida · 2560 px","Mais rápida · 1920 px","Resolução original"};resolution.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,modes));resolution.setSelection(Math.max(0,Math.min(2,prefs.getInt("resolution",0))));resolution.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("resolution",pos).apply();publishLiveSettings();}public void onNothingSelected(android.widget.AdapterView<?> p){}});box.addView(resolution,new LinearLayout.LayoutParams(-1,dp(48)));

  Switch curate=settingSwitch(box,"Curadoria automática",prefs.getBoolean("curationEnabled",true));curate.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("curationEnabled",c).apply();publishLiveSettings();});box.addView(text("Sensibilidade da curadoria",13,TEXT,true));Spinner sensitivity=new Spinner(MainActivity.this);String[] cs={"Baixa","Média","Alta"};sensitivity.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,cs));sensitivity.setSelection(Math.max(0,Math.min(2,prefs.getInt("curationSensitivity",1))));sensitivity.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("curationSensitivity",pos).apply();publishLiveSettings();}public void onNothingSelected(android.widget.AdapterView<?> p){}});box.addView(sensitivity,new LinearLayout.LayoutParams(-1,dp(48)));

  Switch backfill=settingSwitch(box,"Recuperar fotos não baixadas ao reconectar",prefs.getBoolean("backfillEnabled",true));backfill.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("backfillEnabled",c).apply();publishLiveSettings();});
  Switch sound=settingSwitch(box,"Som ao desconectar",prefs.getBoolean("disconnectSound",true));sound.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("disconnectSound",c).apply());

  Button saveProfile=compactButton("Salvar ajustes neste perfil",v->{String name=prefs.getString("workProfile","Personalizado");WorkProfiles.saveCurrent(MainActivity.this,name);message("Perfil "+name+" atualizado.");});box.addView(saveProfile,new LinearLayout.LayoutParams(-1,dp(42)));gap(box,5);
  Button importPreset=compactButton("Importar predefinição XMP",v->{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),10);});box.addView(importPreset,new LinearLayout.LayoutParams(-1,dp(44)));
  new AlertDialog.Builder(MainActivity.this).setTitle("Configurações da sessão").setView(scroll).setPositiveButton("Fechar",(d,n)->{publishLiveSettings();refreshQuickControls();}).show();
 }
 Switch settingSwitch(LinearLayout parent,String title,boolean checked){LinearLayout r=row();TextView t=text(title,14,TEXT,false);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));Switch s=new Switch(MainActivity.this);s.setChecked(checked);r.addView(s);parent.addView(r,new LinearLayout.LayoutParams(-1,dp(48)));return s;}

 static final class PhotoItem{String id,name,uri,state,reason,fottoState="";int score,burstCount;boolean sent;PhotoItem(String i,String n,String u,String st,String r,int sc,boolean se,int bc){id=i;name=n;uri=u;state=st;reason=r;score=sc;sent=se;burstCount=bc;}}
 ArrayList<PhotoItem> galleryItems(){ArrayList<PhotoItem> out=new ArrayList<>();String sql="SELECT j.id,j.name,COALESCE(j.edited,j.original),j.state,COALESCE(j.quality_note,j.error,''),j.score,(SELECT COUNT(*) FROM jobs b WHERE b.burst_id=j.burst_id) FROM jobs j WHERE j.original IS NOT NULL ORDER BY j.rowid DESC LIMIT 80";try(Cursor c=database.getReadableDatabase().rawQuery(sql,null)){while(c.moveToNext())out.add(new PhotoItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),false,c.getInt(6)));}for(PhotoItem item:out){item.fottoState="";ensureLegacyScore(item);}return out;}
 void ensureLegacyScore(PhotoItem item){if(item==null||item.score>0||item.uri==null||item.uri.isEmpty()||!("done".equals(item.state)||"review".equals(item.state))||!scoringIds.add(item.id))return;final String id=item.id,uri=item.uri;scoring.execute(()->{File tmp=new File(getCacheDir(),"score_"+id+".jpg");try{try(InputStream in=getContentResolver().openInputStream(Uri.parse(uri));OutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("Foto indisponível");Jobs.copy(in,out);}int sensitivity=Math.max(0,Math.min(2,getSharedPreferences("hisho",0).getInt("curationSensitivity",1)));SmartCurator.Result q=SmartCurator.analyze(tmp,sensitivity,database,id);database.setIntelligence(id,q.pHash,q.duplicateOf,q.semantic);database.setQuality(id,q.score,q.reason+" · nota recalculada");handler.post(()->{filesSignature="";gallerySignature="";reviewSignature="";if(selected==1)refreshFiles(true);if(selected==2)refreshReview(true);});}catch(Exception ignored){}finally{tmp.delete();scoringIds.remove(id);}});}

 void refreshFiles(boolean force){
  if(galleryGrid==null)return;ArrayList<PhotoItem> items=galleryItems();StringBuilder sig=new StringBuilder();for(PhotoItem x:items)sig.append(x.id).append(':').append(x.state).append(':').append(x.score).append(':').append(x.sent).append(';');sig.append("sel=").append(editSelection.toString());if(!force&&sig.toString().equals(filesSignature))return;filesSignature=sig.toString();galleryGrid.removeAllViews();int cols=getResources().getConfiguration().screenWidthDp>=700?4:3;galleryGrid.setColumnCount(cols);int screenPx=getResources().getDisplayMetrics().widthPixels;int available=screenPx-dp(28)-dp((cols-1)*6);int cell=available/cols;
  if(items.isEmpty()){TextView empty=text("Nenhuma foto ainda.",13,MUTED,false);GridLayout.LayoutParams ep=new GridLayout.LayoutParams();ep.width=-1;ep.height=dp(80);ep.columnSpec=GridLayout.spec(0,cols);galleryGrid.addView(empty,ep);}else for(PhotoItem item:items)addGalleryTile(item,cell);
  updateGallerySelectionUI();
 }
 void addGalleryTile(PhotoItem item,int cell){
  LinearLayout wrap=vertical();GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=cell;gp.height=cell+dp(31);gp.setMargins(dp(3),dp(3),dp(3),dp(5));wrap.setLayoutParams(gp);
  FrameLayout frame=new FrameLayout(MainActivity.this);frame.setBackground(editSelection.contains(item.id)?selectedBorder(CARD,12):border(CARD,12));frame.setClipToOutline(true);wrap.addView(frame,new LinearLayout.LayoutParams(-1,cell));ImageView img=new ImageView(MainActivity.this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(darkMode?0xff20242b:0xffeef1f5,10));frame.addView(img,new FrameLayout.LayoutParams(-1,-1));loadThumb(img,item.uri,Math.max(180,cell));
  TextView state=chip(statusSymbol(item),Color.WHITE,statusColor(item));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);sp.setMargins(0,dp(6),dp(6),0);frame.addView(state,sp);
  TextView score=chip(item.score>0?item.score+"/10":"—",Color.WHITE,item.score>0?scoreColor(item.score):0xff667080);FrameLayout.LayoutParams qp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);qp.setMargins(dp(6),0,0,dp(6));frame.addView(score,qp);if(item.burstCount>1){TextView burst=chip("R "+item.burstCount,Color.WHITE,0xff4d5b73);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);bp.setMargins(0,0,dp(6),dp(6));frame.addView(burst,bp);}
  if(editSelection.contains(item.id)){TextView check=chip("✓",Color.WHITE,GREEN);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);cp.setMargins(dp(6),dp(6),0,0);frame.addView(check,cp);}
  TextView name=text(item.name.replaceFirst("(?i)\\.jpg$",""),10,TEXT,false);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(dp(3),dp(4),dp(3),0);wrap.addView(name,new LinearLayout.LayoutParams(-1,dp(27)));
  frame.setOnLongClickListener(v->{selectionMode=true;editSelection.add(item.id);filesSignature="";refreshFiles(true);return true;});frame.setOnClickListener(v->{if(selectionMode){if(editSelection.contains(item.id))editSelection.remove(item.id);else editSelection.add(item.id);if(editSelection.isEmpty())selectionMode=false;filesSignature="";refreshFiles(true);}else if(item.uri!=null&&!item.uri.isEmpty())showPhotoDetails(item);});galleryGrid.addView(wrap);
 }
 String statusSymbol(PhotoItem x){if("review".equals(x.state))return "!";if("done".equals(x.state))return "✓";return "●";}
 int statusColor(PhotoItem x){if("review".equals(x.state))return WARN;if("done".equals(x.state))return SUCCESS;return GREEN;}
 int scoreColor(int score){if(score>=8)return SUCCESS;if(score>=5)return WARN;return DANGER;}
 void updateGallerySelectionUI(){if(gallerySelectionLabel==null)return;if(editSelection.isEmpty()){gallerySelectionLabel.setText("Toque abre · segure para selecionar");galleryActionBar.setVisibility(View.GONE);}else{gallerySelectionLabel.setText(editSelection.size()+" foto(s) selecionada(s)");galleryActionBar.setVisibility(View.VISIBLE);}}
 void editSelected(){if(editSelection.isEmpty()){message("Selecione uma ou mais fotos.");return;}String id=editSelection.iterator().next();openEditor(id,new ArrayList<>(editSelection),false);}
 void copyGalleryAdjustments(){if(editSelection.isEmpty()){message("Selecione uma foto para copiar os ajustes.");return;}String id=editSelection.iterator().next();String[] j=database.editorJob(id);if(j==null){message("Foto não encontrada.");return;}try{JSONObject settings=new JSONObject(j[2]);JSONObject manual=settings.optJSONObject("manual");if(manual==null)manual=new JSONObject();getSharedPreferences("hisho",0).edit().putString("manualEditClipboard",manual.toString()).apply();message("Refinamento da foto copiado. No editor você pode escolher parâmetros específicos para a próxima cópia.");}catch(Exception e){message("Não foi possível copiar os ajustes.");}}
 void pasteGalleryAdjustments(){if(editSelection.isEmpty()){message("Selecione as fotos que receberão o refinamento.");return;}if(getSharedPreferences("hisho",0).getString("manualEditClipboard","").isEmpty()){message("Copie um refinamento primeiro.");return;}openEditor(editSelection.iterator().next(),new ArrayList<>(editSelection),true);}
 void approveSelected(){if(editSelection.isEmpty()){message("Selecione fotos em revisão.");return;}ArrayList<String[]> targets=new ArrayList<>();for(String id:new ArrayList<>(editSelection)){try(Cursor c=database.getReadableDatabase().rawQuery("SELECT name,edited,state FROM jobs WHERE id=?",new String[]{id})){if(c.moveToFirst()&&"review".equals(c.getString(2)))targets.add(new String[]{id,c.getString(0),c.getString(1)});}}if(targets.isEmpty()){message("Nenhuma foto selecionada está em Revisão.");return;}editSelection.clear();selectionMode=false;for(String[] t:targets)approveReview(t[0],t[1],t[2]);}
 void sendSelected(){if(editSelection.isEmpty()){message("Selecione fotos aprovadas para enviar.");return;}if(!checkFottoReady())return;FottoSync.sendSelected(MainActivity.this,new ArrayList<>(editSelection));message("Fotos selecionadas adicionadas à entrega direta.");}

 void refreshReview(boolean force){
  if(reviewFiles==null)return;int count=database.reviewCount();if(reviewHeading!=null)reviewHeading.setText("Revisão ("+count+")");StringBuilder sig=new StringBuilder();ArrayList<PhotoItem> list=new ArrayList<>();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT id,name,edited,COALESCE(quality_note,error,'Sob revisão'),score FROM jobs WHERE state='review' ORDER BY rowid DESC LIMIT 50",null)){while(c.moveToNext()){PhotoItem p=new PhotoItem(c.getString(0),c.getString(1),c.getString(2),"review",c.getString(3),c.getInt(4),false,database.burstSummary(c.getString(0)).length);list.add(p);sig.append(p.id).append(':').append(p.score).append(':').append(p.reason).append(';');}}
  if(!force&&sig.toString().equals(reviewSignature))return;reviewSignature=sig.toString();reviewFiles.removeAllViews();if(list.isEmpty()){gap(reviewFiles,16);TextView all=text("Nenhuma foto sob revisão. A curadoria está limpa.",14,SUCCESS,true);reviewFiles.addView(all);return;}for(PhotoItem item:list)addReviewRow(item);
 }
 void addReviewRow(PhotoItem item){LinearLayout box=row();box.setPadding(dp(8),dp(8),dp(8),dp(8));box.setBackground(border(CARD,14));ImageView img=new ImageView(MainActivity.this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(CARD2,10));box.addView(img,new LinearLayout.LayoutParams(dp(96),dp(96)));loadThumb(img,item.uri,240);LinearLayout copy=vertical();copy.setPadding(dp(10),0,0,0);LinearLayout top=row();TextView name=text(item.name.replaceFirst("(?i)\\.jpg$",""),13,TEXT,true);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));top.addView(chip(item.score+"/10",Color.WHITE,scoreColor(item.score)));copy.addView(top);gap(copy,5);TextView reason=text(cleanReason(item.reason),11,MUTED,false);reason.setMaxLines(2);reason.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(reason);gap(copy,6);LinearLayout actions=row();Button approve=miniNav("Aprovar",v->approveReview(item.id,item.name,item.uri));primary(approve);actions.addView(approve,new LinearLayout.LayoutParams(0,dp(36),1));Button edit=miniNav("Editar",v->openEditor(item.id,Collections.singletonList(item.id),false));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,dp(36),1);ep.leftMargin=dp(5);actions.addView(edit,ep);copy.addView(actions);box.addView(copy,new LinearLayout.LayoutParams(0,-2,1));box.setOnClickListener(v->showPhotoDetails(item));reviewFiles.addView(box,new LinearLayout.LayoutParams(-1,-2));gap(reviewFiles,7);}
 String cleanReason(String r){if(r==null||r.isEmpty())return "Revisão técnica";return r.replace("Sob revisão: ","");}

 void loadThumb(ImageView image,String uri,int edge){if(uri==null||uri.isEmpty()){image.setImageResource(android.R.drawable.ic_menu_report_image);return;}Bitmap cached=thumbCache.get(uri);if(cached!=null){image.setImageBitmap(cached);return;}final long gen=stripGeneration;thumbnails.execute(()->{if(destroyed)return;Bitmap result=decodeImage(uri,edge);handler.post(()->{if(destroyed)return;if(result!=null){thumbCache.put(uri,result);image.setImageBitmap(result);}else image.setImageResource(android.R.drawable.ic_menu_report_image);});});}

 void refreshGallery(){
  if(filmStrip==null)return;ArrayList<GallerySelection.Photo> list=new ArrayList<>();ArrayList<String[]> meta=new ArrayList<>();StringBuilder signature=new StringBuilder();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT name,edited,state,score FROM jobs WHERE state IN ('done','review') AND edited IS NOT NULL ORDER BY rowid DESC LIMIT 60",null)){while(c.moveToNext()){String uri=c.getString(1);list.add(new GallerySelection.Photo(uri,c.getString(0)));meta.add(new String[]{uri,c.getString(2),String.valueOf(c.getInt(3))});signature.append(uri).append(':').append(c.getString(2)).append(':').append(c.getInt(3)).append(';');}}
  if(signature.toString().equals(gallerySignature)){showSelection(false);return;}gallerySignature=signature.toString();Collections.reverse(list);Collections.reverse(meta);gallery.replace(list);stripGeneration++;filmStrip.removeAllViews();tiles.clear();for(int i=0;i<gallery.photos.size();i++){GallerySelection.Photo photo=gallery.photos.get(i);String[] m=meta.get(i);FrameLayout tile=new FrameLayout(MainActivity.this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(70),dp(70));lp.rightMargin=dp(6);filmStrip.addView(tile,lp);ImageView img=new ImageView(MainActivity.this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(CARD2,10));img.setClipToOutline(true);tile.addView(img,new FrameLayout.LayoutParams(-1,-1));loadThumb(img,photo.uri,160);if(Integer.parseInt(m[2])>0){TextView sc=chip(m[2]+"",Color.WHITE,scoreColor(Integer.parseInt(m[2])));FrameLayout.LayoutParams slp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);slp.setMargins(0,0,dp(4),dp(4));tile.addView(sc,slp);}if("review".equals(m[1])){TextView warn=chip("!",Color.WHITE,WARN);FrameLayout.LayoutParams wlp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);wlp.setMargins(dp(4),dp(4),0,0);tile.addView(warn,wlp);}tile.setOnClickListener(v->{gallery.select(photo.uri);showSelection(true);});tiles.put(photo.uri,tile);}showSelection(gallery.follow);
 }
 void movePhoto(int offset){gallery.move(offset);showSelection(true);}
 void showSelection(boolean scroll){if(previousPhoto==null)return;int index=gallery.index();boolean any=index>=0;previousPhoto.setEnabled(index>0);nextPhoto.setEnabled(any&&index<gallery.photos.size()-1);livePhoto.setEnabled(!gallery.photos.isEmpty());livePhoto.setText(gallery.follow?"● Acompanhando":"Acompanhar novas");livePhoto.setTextColor(gallery.follow?GREEN:TEXT);galleryInfo.setText(gallery.photos.isEmpty()?"As fotos recentes aparecem aqui.":(any?(index+1)+" / "+gallery.photos.size():"—")+" · últimas 60");for(Map.Entry<String,FrameLayout> e:tiles.entrySet()){boolean chosen=e.getKey().equals(gallery.selected);e.getValue().setBackground(chosen?selectedBorder(Color.TRANSPARENT,11):border(Color.TRANSPARENT,11));}if(!gallery.selected.isEmpty())loadPreview(gallery.selected);if(scroll){FrameLayout tile=tiles.get(gallery.selected);if(tile!=null)filmScroll.post(()->filmScroll.smoothScrollTo(Math.max(0,tile.getLeft()-(filmScroll.getWidth()-tile.getWidth())/2),0));}}
 Bitmap decodeImage(String value,int edge){try{Uri uri=Uri.parse(value);BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opts);}if(opts.outWidth<=0||opts.outHeight<=0)return null;opts.inJustDecodeBounds=false;opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>edge)opts.inSampleSize*=2;try(InputStream in=getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,opts);}}catch(Exception e){return null;}}
 void loadPreview(String value){if(value.equals(requestedUri))return;requestedUri=value;final long gen=++previewGeneration;preview.setImageDrawable(null);shownUri="";previewTitle.setText(gallery.name());previewSub.setText("Carregando…");images.execute(()->{if(destroyed||gen!=previewGeneration)return;final Bitmap ready=decodeImage(value,1500);String id=database.idForEdited(value);int score=id.isEmpty()?0:database.quality(id)[0];String st="";try(Cursor c=database.getReadableDatabase().rawQuery("SELECT state FROM jobs WHERE id=?",new String[]{id})){if(c.moveToFirst())st=c.getString(0);}final String state=st;handler.post(()->{if(destroyed||gen!=previewGeneration){if(ready!=null)ready.recycle();return;}empty.setVisibility(View.GONE);if(ready==null){previewSub.setText("Arquivo indisponível");return;}if(previewBitmap!=null&&previewBitmap!=ready)previewBitmap.recycle();previewBitmap=ready;preview.setImageBitmap(ready);shownUri=value;previewTitle.setText(gallery.name());previewSub.setText(("review".equals(state)?"Revisão":"Pronta")+(score>0?" · nota "+score+"/10":"")+" · toque para ampliar");});});}
 void fullscreen(){if(!shownUri.isEmpty())openImage(shownUri);}

 String exportRootLabel(){String raw=getSharedPreferences("hisho",0).getString("exportTreeUri","");if(raw.isEmpty())return "Pictures / LUMO";Uri uri=Uri.parse(raw);String name=uri.getLastPathSegment();if(name==null||name.isEmpty())name=raw;try{name=java.net.URLDecoder.decode(name,"UTF-8");}catch(Exception ignored){}int colon=name.lastIndexOf(':');if(colon>=0&&colon+1<name.length())name=name.substring(colon+1);return name;}
 void confirmClearEdited(){
  new AlertDialog.Builder(MainActivity.this)
   .setTitle("Limpar pasta Editadas?")
   .setMessage("Isso apaga todas as fotos da pasta Editadas usada pelo Fotto.\n\nOs arquivos Originais, as fotos em Revisão, as notas e o histórico do Lumo serão preservados.\n\nUse isto ao iniciar um novo evento ou quando quiser zerar a pasta monitorada.")
   .setPositiveButton("Limpar agora",(d,n)->clearEditedFolder())
   .setNegativeButton("Cancelar",null)
   .show();
 }

 void clearEditedFolder(){
  message("Limpando pasta Editadas…");
  images.execute(()->{
   try{
    int count=database.clearEditedFolder();
    getSharedPreferences("hisho",0).edit()
     .remove("fottoWebLastActivity")
     .apply();
    handler.post(()->{
     gallerySignature="";filesSignature="";reviewSignature="";
     refreshGallery();refreshFiles(true);refreshReview(true);updateGlobalStatus();
     message(count==0?"A pasta Editadas já estava vazia.":count+" foto(s) removida(s) da pasta Editadas.");
    });
   }catch(Exception e){
    handler.post(()->message("Não foi possível limpar a pasta Editadas: "+e.getMessage()));
   }
  });
 }

 void chooseExportFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(i,20);}
 void updateExportFolder(){if(folderLabel==null)return;String raw=getSharedPreferences("hisho",0).getString("exportTreeUri","");folderLabel.setText(raw.isEmpty()?"Pictures / LUMO":exportRootLabel());}
 boolean checkFottoReady(){if(FottoApi.token(MainActivity.this).isEmpty()){message("Conecte sua conta Fotto no menu ⋯.");return false;}if(getSharedPreferences("hisho",0).getString("fottoGalleryId","").isEmpty()){message("Escolha um evento Fotto primeiro.");return false;}return true;}
 void deliverMenu(){boolean connected=!FottoApi.token(MainActivity.this).isEmpty();String[] items={connected?"Reconectar conta Fotto":"Conectar conta Fotto","Trocar evento","Atualizar eventos","Enviar todas as pendentes","Abrir Fotto Web · fallback","Alterar pasta de exportação"};new AlertDialog.Builder(MainActivity.this).setTitle("Entrega direta").setItems(items,(d,n)->{if(n==0)startActivityForResult(new Intent(MainActivity.this,FottoLoginActivity.class),30);else if(n==1){if(!connected){message("Conecte o Fotto primeiro.");return;}if(fottoGalleries.isEmpty()){openEventChooserAfterLoad=true;loadFottoGalleries();}else showEventChooser();}else if(n==2)loadFottoGalleries();else if(n==3){if(checkFottoReady())FottoSync.sendExisting(MainActivity.this);}else if(n==4)openFottoWeb();else chooseExportFolder();}).show();}
 void showEventChooser(){if(fottoGalleries.isEmpty()){message("Nenhum evento encontrado.");return;}String[] names=new String[fottoGalleries.size()];String saved=getSharedPreferences("hisho",0).getString("fottoGalleryId","");int checked=-1;for(int i=0;i<names.length;i++){names[i]=fottoGalleries.get(i).title;if(fottoGalleries.get(i).id.equals(saved))checked=i;}new AlertDialog.Builder(MainActivity.this).setTitle("Evento de destino").setSingleChoiceItems(names,checked,(d,which)->{FottoApi.Gallery g=fottoGalleries.get(which);android.content.SharedPreferences p=getSharedPreferences("hisho",0);String old=p.getString("fottoGalleryId","");android.content.SharedPreferences.Editor e=p.edit().putString("fottoGalleryId",g.id).putString("fottoGalleryTitle",g.title);if(!g.id.equals(old)&&p.getBoolean("fottoAuto",false))e.putLong("fottoStartRowid",database.maxRowId());e.apply();d.dismiss();updateFottoStatus();FottoSync.kick(MainActivity.this);}).setNegativeButton("Cancelar",null).show();}
 void updateFottoStatus(){if(fottoStatus==null)return;android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);String token=FottoApi.token(MainActivity.this),galleryId=prefs.getString("fottoGalleryId","");String galleryTitle=prefs.getString("fottoGalleryTitle","");boolean active=prefs.getBoolean("fottoAuto",false);fottoStatus.setText(token.isEmpty()?"Fotto • Não conectado":FottoSync.running()?"Fotto • Enviando…":"Fotto • Conectado");fottoStatus.setTextColor(token.isEmpty()?MUTED:(FottoSync.running()?WARN:SUCCESS));fottoEventLabel.setText("Evento: "+(galleryTitle.isEmpty()?"—":galleryTitle)+(galleryId.isEmpty()?"":" · "+galleryId));int sent=galleryId.isEmpty()?prefs.getInt("fottoUploadedCount",0):database.fottoDone(galleryId);int pending=galleryId.isEmpty()?0:database.fottoPending(galleryId);int processing=galleryId.isEmpty()?0:database.fottoProcessing(galleryId);String last=prefs.getString("fottoLastStatus",active?"Envio automático ativo":"Envio automático pausado");fottoUploadStatus.setText(sent+" confirmadas · "+pending+" na fila"+(processing>0?" · "+processing+" processando":"")+(FottoSync.running()?" · sincronizando…":"")+"\n"+last);String dbError=galleryId.isEmpty()?"":database.fottoLastError(galleryId);boolean failure=(last!=null&&(last.startsWith("Falha")||last.contains("erro")||last.contains("Erro")||last.contains("Não confirmada")))||!dbError.isEmpty();fottoProblems.setVisibility(failure?View.VISIBLE:View.GONE);if(failure)fottoProblems.setText("Problemas\n"+(!dbError.isEmpty()?dbError:last));if(fottoAuto!=null&&fottoAuto.isChecked()!=active){fottoAuto.setOnCheckedChangeListener(null);fottoAuto.setChecked(active);bindFottoToggle();}}
 void bindFottoToggle(){if(fottoAuto==null)return;fottoAuto.setOnCheckedChangeListener((b,checked)->{if(checked){if(FottoApi.token(MainActivity.this).isEmpty()){message("Conecte o Fotto antes de ativar o envio automático.");getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",false).apply();b.setChecked(false);return;}String galleryId=getSharedPreferences("hisho",0).getString("fottoGalleryId","");if(galleryId.isEmpty()){message("Escolha um evento antes de ativar o envio automático.");getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",false).apply();b.setChecked(false);return;}getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",true).putLong("fottoStartRowid",database.maxRowId()).putString("fottoLastStatus","Envio direto automático ativo").apply();FottoSync.kick(MainActivity.this);}else getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",false).putString("fottoLastStatus","Envio direto automático pausado").apply();updateFottoStatus();});}
 void loadFottoGalleries(){if(FottoApi.token(MainActivity.this).isEmpty()){updateFottoStatus();return;}fottoStatus.setText("Fotto • Carregando eventos…");fottoNetwork.execute(()->{try{ArrayList<FottoApi.Gallery> list=FottoApi.galleries(MainActivity.this);handler.post(()->{if(destroyed)return;fottoGalleries.clear();fottoGalleries.addAll(list);String saved=getSharedPreferences("hisho",0).getString("fottoGalleryId","");boolean exists=false;for(FottoApi.Gallery g:list)if(g.id.equals(saved)){exists=true;break;}if(!exists&&!list.isEmpty()){FottoApi.Gallery g=list.get(0);getSharedPreferences("hisho",0).edit().putString("fottoGalleryId",g.id).putString("fottoGalleryTitle",g.title).apply();}if(list.isEmpty())getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Nenhum evento encontrado nesta conta.").apply();updateFottoStatus();if(openEventChooserAfterLoad){openEventChooserAfterLoad=false;showEventChooser();}});}catch(Exception e){handler.post(()->{if(destroyed)return;getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Falha ao carregar eventos · "+e.getMessage()).apply();updateFottoStatus();});}});}
 String num(int n){return n<0?"—":String.valueOf(n);}



 void openEditor(String focusId,Collection<String> ids,boolean paste){if(focusId==null||focusId.isEmpty()){message("Foto não encontrada no histórico.");return;}LinkedHashSet<String> unique=new LinkedHashSet<>();if(ids!=null)unique.addAll(ids);unique.add(focusId);StringBuilder packed=new StringBuilder();for(String id:unique){if(packed.length()>0)packed.append(',');packed.append(id);}Intent i=new Intent(MainActivity.this,ManualEditorActivity.class).putExtra("focusId",focusId).putExtra("jobIds",packed.toString()).putExtra("pasteOnOpen",paste);startActivity(i);}
 void approveReview(String id,String name,String reviewUri){message("Aprovando foto…");images.execute(()->{File tmp=new File(getCacheDir(),"approve_"+id+".jpg");boolean cleared=false;try(InputStream in=getContentResolver().openInputStream(Uri.parse(reviewUri));OutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("Foto de revisão indisponível.");Jobs.copy(in,out);database.clearEdited(id);cleared=true;Uri ready=database.save(tmp,"Editadas",name.replaceFirst("(?i)\\.jpg$","_Editada.jpg"),id,"edited");database.set(id,"done","error",null);database.history(id,System.currentTimeMillis(),"Aprovada","Aprovação manual na Revisão");try{getContentResolver().delete(Uri.parse(reviewUri),null,null);}catch(Exception ignored){}FottoSync.kick(MainActivity.this);handler.post(()->{gallerySignature="";filesSignature="";reviewSignature="";refreshGallery();refreshFiles(true);refreshReview(true);message("Foto aprovada e liberada para entrega.");});}catch(Exception e){if(cleared)database.set(id,"review","edited",reviewUri);handler.post(()->message("Não foi possível aprovar: "+e.getMessage()));}finally{tmp.delete();}});}
 void openImage(String uri){try{Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}catch(Exception e){message("Não foi possível abrir esta foto.");}}
 void diagnostic(){TextView t=text(report==null?"":report.getText().toString(),12,MUTED,false);t.setTextIsSelectable(true);t.setPadding(dp(16),dp(16),dp(16),dp(16));ScrollView scroll=new ScrollView(MainActivity.this);scroll.addView(t);new AlertDialog.Builder(MainActivity.this).setTitle("Diagnóstico da sessão").setView(scroll).setPositiveButton("Copiar",(d,n)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("LUMO",t.getText()));message("Diagnóstico copiado.");}).setNegativeButton("Fechar",null).show();}

 class LumoMark extends View{Paint p=new Paint(3);LumoMark(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),r=Math.min(w,h)*.34f;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.min(w,h)*.24f);p.setStrokeCap(Paint.Cap.ROUND);p.setShader(new SweepGradient(w/2f,h/2f,new int[]{0xff22dff3,0xff0072ff,0xff2336d7,0xff22dff3},null));c.drawCircle(w/2f,h/2f,r,p);p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(CARD);c.drawCircle(w*.60f,h*.43f,Math.min(w,h)*.12f,p);}}
 class CameraArt extends View{Paint paint=new Paint(3);CameraArt(Context c){super(c);}protected void onDraw(Canvas canvas){super.onDraw(canvas);float sx=getWidth()/110f,sy=getHeight()/100f;canvas.save();canvas.scale(sx,sy);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff172231);canvas.drawCircle(55,50,45,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(CYAN);canvas.drawRoundRect(24,34,86,73,8,8,paint);canvas.drawCircle(55,54,13,paint);canvas.drawCircle(55,54,7,paint);canvas.drawLine(36,33,41,25,paint);canvas.drawLine(41,25,64,25,paint);canvas.drawLine(64,25,70,33,paint);canvas.restore();}}

 void chooseTransport(){new AlertDialog.Builder(MainActivity.this).setTitle("Conectar câmera").setItems(new String[]{"Cabo USB","Wi-Fi · experimental"},(d,n)->{if(n==0)choose();else wifiDialog();}).show();}
 void wifiDialog(){android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);android.net.Network found=null;String gateway="192.168.0.1";for(android.net.Network n:cm.getAllNetworks()){android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)){found=n;android.net.LinkProperties props=cm.getLinkProperties(n);if(props!=null)for(android.net.RouteInfo route:props.getRoutes())if(route.isDefaultRoute()&&route.getGateway() instanceof java.net.Inet4Address)gateway=route.getGateway().getHostAddress();break;}}final android.net.Network wifi=found;LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));label(box,"Na câmera: Wi-Fi → Controle remoto/EOS Utility. Conecte o celular à rede da câmera e autorize o LUMO.",13);EditText ip=new EditText(MainActivity.this);ip.setText(getSharedPreferences("hisho",0).getString("wifiHost",gateway));ip.setTextColor(TEXT);ip.setHint("IP da câmera");ip.setInputType(android.text.InputType.TYPE_CLASS_PHONE);box.addView(ip);AlertDialog dialog=new AlertDialog.Builder(MainActivity.this).setTitle("Conectar por Wi-Fi").setView(box).setPositiveButton("Conectar",null).setNeutralButton("Abrir Wi-Fi",(d,n)->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))).setNegativeButton("Cancelar",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String host=ip.getText().toString().trim();if(!validIp(host)){ip.setError("Informe um IPv4 válido.");return;}if(wifi==null){message("Conecte ao Wi-Fi da câmera primeiro.");return;}getSharedPreferences("hisho",0).edit().putString("wifiHost",host).apply();dialog.dismiss();startCamera(null,host,wifi);}));dialog.show();}
 static boolean validIp(String host){String[] parts=host.split("\\.",-1);if(parts.length!=4)return false;for(String part:parts){if(!part.matches("[0-9]{1,3}"))return false;int v=Integer.parseInt(part);if(v>255)return false;}return true;}
 void choose(){UsbManager m=(UsbManager)getSystemService(USB_SERVICE);ArrayList<UsbDevice> ds=new ArrayList<>();for(UsbDevice d:m.getDeviceList().values())if(d.getVendorId()==0x04a9)ds.add(d);if(ds.isEmpty()){message("Nenhuma Canon encontrada. Conecte um cabo USB/OTG de dados.");return;}if(ds.size()==1){permission(ds.get(0));return;}String[] names=new String[ds.size()];for(int i=0;i<ds.size();i++)names[i]=ds.get(i).getProductName();new AlertDialog.Builder(MainActivity.this).setTitle("Escolha a câmera").setItems(names,(d,n)->permission(ds.get(n))).show();}
 void permission(UsbDevice d){UsbManager m=(UsbManager)getSystemService(USB_SERVICE);if(m.hasPermission(d)){start(d);return;}Intent i=new Intent(USB).setPackage(getPackageName());PendingIntent pi=PendingIntent.getBroadcast(MainActivity.this,1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);m.requestPermission(d,pi);}
 void start(UsbDevice d){startCamera(d,null,null);}
 void startCamera(UsbDevice d,String wifiHost,android.net.Network wifiNetwork){if(CaptureService.active!=null){message("Serviço já ativo.");return;}try{JSONObject cfg=buildCurrentSettings();getSharedPreferences("hisho",0).edit().putString("liveSettings",cfg.toString()).apply();Intent i=new Intent(MainActivity.this,CaptureService.class).putExtra("settings",cfg.toString());if(d!=null)i.putExtra("device",d);if(wifiHost!=null)i.putExtra("wifiHost",wifiHost).putExtra("wifiNetwork",wifiNetwork);startForegroundService(i);}catch(Exception e){message(e.getMessage());}}
 static String read(InputStream stream)throws IOException{try(InputStream s=stream;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] a=new byte[8192];int n;while((n=s.read(a))!=-1){if(b.size()+n>1024*1024)throw new IOException("Arquivo maior que 1 MB.");b.write(a,0,n);}return b.toString("UTF-8");}}

 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==30){if(result==RESULT_OK){getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Conta conectada. Carregando eventos…").apply();loadFottoGalleries();updateFottoStatus();}return;}
  if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(uri,flags);getSharedPreferences("hisho",0).edit().putString("exportTreeUri",uri.toString()).apply();updateExportFolder();message("Pasta de exportação salva.");}catch(Exception e){message("Não foi possível manter acesso à pasta: "+e.getMessage());}return;}
  if(request==10&&result==RESULT_OK&&data!=null)try{JSONObject parsed=PhotoEditor.parse(read(getContentResolver().openInputStream(data.getData())));presetData=parsed;presetName="Predefinição importada";try(Cursor c=getContentResolver().query(data.getData(),new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())presetName=c.getString(0).replaceFirst("(?i)\\.xmp$","");}getSharedPreferences("hisho",0).edit().putString("preset",parsed.toString()).putString("presetName",presetName).putBoolean("presetEnabled",true).apply();PresetLibrary.put(MainActivity.this,presetName,parsed);publishLiveSettings();refreshQuickControls();message("Predefinição "+presetName+" importada.");}catch(Exception e){message("Predefinição anterior mantida: "+e.getMessage());}
 }
 void message(String s){Toast.makeText(MainActivity.this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();QueueRecovery.resume(MainActivity.this);gallerySignature="";filesSignature="";reviewSignature="";requestedUri="";thumbCache.evictAll();handler.removeCallbacks(update);handler.post(update);}
 @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
 @Override protected void onSaveInstanceState(Bundle state){state.putInt("page",selected);state.putBoolean("follow",gallery.follow);state.putString("photo",gallery.selected);super.onSaveInstanceState(state);}
 @Override protected void onDestroy(){destroyed=true;previewGeneration++;stripGeneration++;handler.removeCallbacks(update);thumbnails.shutdownNow();images.shutdownNow();scoring.shutdownNow();fottoNetwork.shutdownNow();thumbCache.evictAll();if(previewBitmap!=null)previewBitmap.recycle();database.close();if(registered)unregisterReceiver(receiver);super.onDestroy();}
}
