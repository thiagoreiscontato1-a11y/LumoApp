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
import android.provider.DocumentsContract;
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

 private TextView demoStatus;
 private TextView flowStatus,badge,status,timing,previewTitle,previewSub,galleryInfo,gallerySelectionLabel,reviewHeading,fottoLiveStatus;
 private TextView metricBattery,metricStorage,metricPending,metricProcessed,metricSent;
 private TextView folderLabel,fottoStatus,fottoEventLabel,fottoUploadStatus,fottoProblems;
 private Button connect,resume,settingsButton,themeButton,folderButton,fottoMenu,fottoSend,healthButton,eventModeButton,presetQuick;
 private LinearLayout importBar;private TextView importBarText;private ProgressBar importBarProgress;private Button importCancelButton;
 private CompoundButton fottoAuto;
 private boolean openEventChooserAfterLoad=false;
 private boolean fottoGalleriesLoading=false;
 private String fottoEventsSummary="";
 private final ArrayList<FottoApi.Gallery> fottoGalleries=new ArrayList<>();
 private final ExecutorService fottoNetwork=Executors.newSingleThreadExecutor();

 private final LinearLayout[] pages=new LinearLayout[4];
 private final ScrollView[] scrollers=new ScrollView[4];
 private final Button[] tabs=new Button[4];
 private int selected=0;

 private FrameLayout previewFrame;
 private LinearLayout empty,filmStrip,reviewFiles,galleryActionBar;
 private GridLayout galleryGrid;
 private int galleryOffset=0;
 private ListView galleryList;
 private BaseAdapter galleryAdapter;
 private final ArrayList<PhotoItem> galleryLoaded=new ArrayList<>();
 private final ExecutorService galleryWorker=Executors.newSingleThreadExecutor();
 private boolean galleryLoading=false;
 private int galleryGeneration=0,galleryKnownTotal=0;
 private ProgressBar gallerySpinner;
 private TextView galleryLoadingLabel;
 private String galleryAlbum="";
 private TextView eventTitle,previewScore,liveChip;
 private LinearLayout galleryEmpty;private Button fottoStop;
 private View previewFooter;
 private Button clearSelection;
 private TextView[] tileFotos,tileReview,tileFotto;
 static final int REQUEST_VIEWER=52;
 // Estado da importação por pasta é estático: sobrevive à recriação da tela (girar o aparelho) e o botão Cancelar continua valendo.
 private static volatile boolean importingEvent=false,importCancelRequested=false;
 private static volatile int importFound=0,importProcessed=0,importTotal=0;
 private static volatile boolean importScanning=false;
 private Button albumButton;
 private boolean deletingPhotos=false;
 private TextView galleryPageLabel;
 private ImageView preview;
 private Bitmap previewBitmap;
 private String shownUri="",requestedUri="",gallerySignature="",filesSignature="",reviewSignature="";
 private volatile long previewGeneration=0,stripGeneration=0;
 private volatile boolean destroyed=false;
 /** onCreate mandou para o login antes de montar a tela: onDestroy não deve tocar no que não foi criado. */
 private boolean gated=false;
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
  if(destroyed||!DemoLicense.allowed(MainActivity.this))return;
  if(demoStatus!=null)demoStatus.setText(DemoLicense.status(MainActivity.this));
  CaptureService s=CaptureService.active;boolean running=s!=null;
  if(connect!=null){connect.setText(!running?"Conectar câmera":s.capturing?s.cameraName+" · "+s.received+(s.received==1?" foto":" fotos")+" · Encerrar":"Concluindo fila…");connect.setTextColor(running?TEXT:LumoTheme.ON_ACCENT);connect.setEnabled(!running||s.capturing);connect.setAlpha(connect.isEnabled()?1f:.45f);connect.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),running?LumoTheme.card(CARD2,dp(18),dp(1)):LumoTheme.gradient(dp(18)),null));}
  if(settingsButton!=null){settingsButton.setEnabled(!running);settingsButton.setAlpha(running?.4f:1f);}
  if(resume!=null){boolean show=!running&&database.pending()>0;resume.setVisibility(show?View.VISIBLE:View.GONE);}
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);
  if(status!=null){if(running){status.setText(s.status);timing.setText(s.detail.isEmpty()?"Captura e edição em filas independentes":s.detail);report.setText(s.report());setBadge(s.connectionLabel());}else{status.setText(prefs.getString("lastStatus","Pronto para conectar a câmera."));timing.setText("Original preservado · edição e curadoria em segundo plano");report.setText(prefs.getString("lastReport","Nenhuma atividade registrada."));setBadge("SEM CÂMERA");}}
  updateGlobalStatus();
  updateImportBar();
  FottoSync.watchdog(MainActivity.this);
  refreshGallery();
  long now=SystemClock.elapsedRealtime();if(now-lastUiRefresh>1500){lastUiRefresh=now;if(selected==1)refreshFiles(false);if(selected==2)refreshReview(false);if(selected==3)updateFottoStatus();}
  handler.postDelayed(this,700);
 }};

 int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
 /** Sempre escuro, igual ao iPhone: as fotos dão a cor; azul/ciano do logo como destaque. */
 void applyPalette(){darkMode=true;BG=LumoTheme.BG;CARD=LumoTheme.SURFACE;CARD2=LumoTheme.SURFACE_STRONG;LINE=LumoTheme.LINE;TEXT=LumoTheme.TEXT;MUTED=LumoTheme.SECONDARY;GREEN=LumoTheme.BLUE;CYAN=LumoTheme.CYAN;SUCCESS=LumoTheme.OK;WARN=LumoTheme.WARN;DANGER=LumoTheme.DANGER;DARK=0xff000000;}
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
 Button compactButton(String value,View.OnClickListener listener){Button b=new Button(MainActivity.this);b.setText(value);b.setTextSize(13);b.setTextColor(TEXT);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x180b7cff),border(CARD2,12),null));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));b.setPadding(dp(14),dp(8),dp(14),dp(8));b.setOnClickListener(listener);return b;}
 void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(LumoTheme.BLUE_SOLID,12),null));}
 Drawable icon(String name,int color){return LumoTheme.icon(MainActivity.this,name,color,dp(24));}
 /** Botão só de ícone: alvo de 48dp, nome para o TalkBack (e dica ao segurar) e ripple. */
 ImageButton iconButton(String name,String description,int tint,View.OnClickListener l){ImageButton b=new ImageButton(MainActivity.this);b.setImageDrawable(icon(name,tint));b.setScaleType(ImageView.ScaleType.CENTER);b.setContentDescription(description);b.setTooltipText(description);b.setPadding(0,0,0,0);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),LumoTheme.fill(CARD,dp(24)),null));b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));b.setOnClickListener(l);return b;}
 /** Ícone antes do texto (botões de largura automática). */
 void leadingIcon(TextView b,String name,int tint){b.setCompoundDrawablesRelative(icon(name,tint),null,null,null);b.setCompoundDrawablePadding(dp(8));}
 /** Retorno tátil curto nas ações que mudam uma foto ou o fluxo. */
 void haptic(View v){if(v!=null)v.performHapticFeedback(Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.VIRTUAL_KEY);}
 /** Pílula de estado da câmera: ponto colorido + rótulo curto, para caber ao lado do nome do evento. */
 void setBadge(String label){if(badge==null)return;boolean live="CONECTADA".equals(label),idle="SEM CÂMERA".equals(label);String shortLabel=live?"Conectada":idle?"Sem câmera":label.charAt(0)+label.substring(1).toLowerCase(Locale.ROOT);if(!shortLabel.contentEquals(badge.getText())){badge.setText(shortLabel);badge.setCompoundDrawablesRelative(LumoTheme.dot(live?SUCCESS:idle?LumoTheme.TERTIARY:WARN,dp(8)),null,null,null);badge.setContentDescription("Câmera: "+shortLabel+". Toque para ver a saúde do fluxo.");}badge.setTextColor(live?SUCCESS:idle?LumoTheme.SECONDARY:WARN);}
 TextView chip(String value,int fg,int bg){TextView t=text(value,10,fg,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(7),dp(4),dp(7),dp(4));t.setBackground(shape(bg,10));return t;}
 TextView metricChip(String title){TextView t=text(title+"\n—",10,TEXT,true);t.setGravity(Gravity.CENTER);t.setLines(2);t.setLineSpacing(dp(1),1f);t.setPadding(dp(5),dp(6),dp(5),dp(6));t.setBackground(border(CARD2,12));return t;}
 void addMetric(LinearLayout row,TextView view,boolean first){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1f);if(!first)lp.leftMargin=dp(6);row.addView(view,lp);}
 void buildHeaderMetrics(LinearLayout header){
  metricBattery=metricChip("BATERIA");metricStorage=metricChip("ESPAÇO");metricPending=metricChip("PENDENTES");metricProcessed=metricChip("PROCESSADAS");metricSent=metricChip("ENVIADAS");
  if(compactHeader()){
   LinearLayout a=row();addMetric(a,metricBattery,true);addMetric(a,metricStorage,false);header.addView(a,new LinearLayout.LayoutParams(-1,-2));
   gap(header,6);LinearLayout b=row();addMetric(b,metricPending,true);addMetric(b,metricProcessed,false);addMetric(b,metricSent,false);header.addView(b,new LinearLayout.LayoutParams(-1,-2));
  }else{
   LinearLayout a=row();addMetric(a,metricBattery,true);addMetric(a,metricStorage,false);addMetric(a,metricPending,false);addMetric(a,metricProcessed,false);addMetric(a,metricSent,false);header.addView(a,new LinearLayout.LayoutParams(-1,-2));
  }
 }

 @Override public void onCreate(Bundle saved){
  darkMode=true;if(getSharedPreferences("hisho",0).getBoolean("eventMode",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setTheme(LumoTheme.appTheme(this));super.onCreate(saved);
  // Login: sem sessão Fotto o Lumo abre na tela de entrada. O modo demonstração (capturas do CI) entra sem conta.
  if(getIntent()!=null&&getIntent().getBooleanExtra("lumoDemo",false))LumoSession.enterDemo(this);
  if(!LumoSession.signedIn(this)){gated=true;startActivity(new Intent(this,LumoLoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();overridePendingTransition(0,0);return;}
  applyPalette();database=new Jobs(MainActivity.this);loadPreset();PresetLibrary.seed(MainActivity.this,presetName,presetData);QueueRecovery.resume(MainActivity.this);
  if(saved!=null){gallery.follow=saved.getBoolean("follow",true);gallery.selected=saved.getString("photo","");}
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(0);
  LinearLayout root=vertical();root.setBackgroundColor(BG);root.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in.consumeSystemWindowInsets();});setContentView(root);

  // Topo: evento atual, estado da câmera e menu — leitura de relance, como no iPhone.
  LinearLayout bar=row();bar.setPadding(dp(16),dp(10),dp(12),dp(8));
  LinearLayout eventBox=vertical();TextView eventLabel=text("EVENTO",10,LumoTheme.TERTIARY,true);eventLabel.setLetterSpacing(.14f);eventBox.addView(eventLabel);
  eventTitle=text(database.eventName(),19,TEXT,true);eventTitle.setSingleLine(true);eventTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);eventTitle.setCompoundDrawablesRelative(null,null,icon("expand",LumoTheme.SECONDARY),null);eventTitle.setCompoundDrawablePadding(dp(2));eventBox.addView(eventTitle,new LinearLayout.LayoutParams(-2,-2));eventBox.setMinimumHeight(dp(48));eventBox.setGravity(Gravity.CENTER_VERTICAL);eventBox.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),null,LumoTheme.fill(Color.WHITE,dp(12))));eventBox.setOnClickListener(v->chooseLocalEvent());
  bar.addView(eventBox,new LinearLayout.LayoutParams(0,-2,1));
  badge=text("",13,LumoTheme.SECONDARY,true);badge.setGravity(Gravity.CENTER);badge.setSingleLine(true);badge.setPadding(dp(12),0,dp(14),0);badge.setMinHeight(dp(48));badge.setCompoundDrawablePadding(dp(7));badge.setBackground(LumoTheme.card(CARD,dp(24),dp(1)));badge.setOnClickListener(v->showHealthPanel());setBadge("SEM CÂMERA");
  LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.leftMargin=dp(8);bar.addView(badge,bp);
  ImageButton menu=iconButton("menu","Menu",TEXT,v->showMainMenu());
  LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(48),dp(48));mp.leftMargin=dp(8);bar.addView(menu,mp);
  root.addView(bar);
  // Indicadores detalhados (bateria, espaço…) ficam no painel Saúde; mantidos aqui ocultos para atualização.
  LinearLayout header=vertical();flowStatus=text("Canon • Sem câmera",11,MUTED,true);header.addView(flowStatus);buildHeaderMetrics(header);header.setVisibility(View.GONE);root.addView(header);
  if(DemoLicense.isDemo(this)){demoStatus=text(DemoLicense.status(this),12,CYAN,true);demoStatus.setPadding(dp(16),0,dp(16),dp(4));root.addView(demoStatus);}
  buildImportBar(root);

  FrameLayout content=new FrameLayout(MainActivity.this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
  for(int i=0;i<4;i++){scrollers[i]=new ScrollView(MainActivity.this);scrollers[i].setFillViewport(true);scrollers[i].setVerticalScrollBarEnabled(false);pages[i]=vertical();pages[i].setPadding(dp(14),dp(12),dp(14),dp(18));scrollers[i].addView(pages[i]);content.addView(scrollers[i],new FrameLayout.LayoutParams(-1,-1));}
  capturePage();galleryPage();reviewPage();fottoPage();

  // Sem abas: a tela "ao vivo" é a principal; Galeria, Revisão e Entrega abrem pelos cartões e voltam pelo ‹.
  select(saved==null?0:saved.getInt("page",0));

  report=new TextView(MainActivity.this);
  IntentFilter filter=new IntentFilter(USB);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,filter);registered=true;
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},20);
  if(getSharedPreferences("hisho",0).getBoolean("eventMode",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);handler.post(update);
  if(getIntent()!=null&&getIntent().getBooleanExtra("lumoDemo",false)){getSharedPreferences("hisho",0).edit().putBoolean("tourSeen",true).apply();handler.post(this::demoSeed);}
  else if(DemoLicense.allowed(this)&&!getSharedPreferences("hisho",0).getBoolean("tourSeen",false))handler.post(()->showTour(0));
  if(LumoSession.account(this).isEmpty())refreshAccount();
  if(getIntent()!=null&&getIntent().hasExtra("lumoPage"))handler.post(()->select(getIntent().getIntExtra("lumoPage",0)));
  if(getIntent()!=null&&getIntent().getBooleanExtra("lumoViewer",false))handler.postDelayed(this::openViewerFromLive,2500);
  // Capturas do CI: abre um modal específico (menu, saude, eventos, tour).
  if(getIntent()!=null&&getIntent().hasExtra("lumoSheet")){String which=getIntent().getStringExtra("lumoSheet");handler.postDelayed(()->{if(destroyed)return;if("menu".equals(which))showMainMenu();else if("saude".equals(which))showHealthPanel();else if("eventos".equals(which))chooseLocalEvent();else if("tour".equals(which))showTour(1);},2500);}
 }

 private Dialog tourDialog;
 void showTour(int step){
  if(isFinishing()||destroyed||!DemoLicense.allowed(this))return;
  String[][] steps={
   {"0","Primeiro, crie um evento","Toque em Evento no topo e em Criar evento. Dê um nome e selecione. Captura e importação exigem um evento. Cada evento reúne seus álbuns, originais, editadas e revisão. Encerre a captura antes de trocar. Fotos antigas ficam em Fotos anteriores. O evento do Fotto é configurado separadamente na aba Entrega."},
   {"0","Bem-vindo ao Lumo","O fluxo é: receber fotos → editar → revisar → entregar. Este tour abre as telas reais sem conectar câmera, alterar fotos ou enviar arquivos. Use Próximo, Voltar ou Sair do tour."},
   {"0","Conectar a câmera","Toque em Conectar câmera, escolha Cabo USB e autorize o acesso. Use um cabo de dados/OTG e fotografe normalmente. Wi-Fi é experimental e exige conexão à rede da câmera e autorização nela."},
   {"0","Importar do cartão USB","Conecte o leitor ao aparelho, toque em Importar fotos do cartão USB e escolha a pasta, como DCIM. O Lumo procura nas subpastas e ignora fotos já importadas. RAW é preservado; a edição usa a prévia JPEG embutida quando disponível, não uma revelação RAW completa."},
   {"0","Predefinições e ajustes automáticos","Toque em Predefinição para escolher um preset ou importar XMP. Em Ajustes de captura e edição, no menu, configure edição automática, intensidade, resolução, curadoria e fotos anteriores. As opções afetam as próximas fotos; fotos antigas podem ser reeditadas na Galeria."},
   {"0","Modo evento","No menu, toque em Ligar modo evento para manter a tela ligada e ativar o conjunto de opções de trabalho. Se a conta e o evento do Fotto já estiverem configurados, esse modo também pode ativar o envio automático. Configure a Entrega antes de usar em um evento real."},
   {"0","Prévia e fila","A última foto aparece em destaque e a tira mostra as recentes. Toque na prévia para abrir o visualizador e deslize para navegar; Voltar ao vivo retoma a foto mais nova. Retomar edições pendentes aparece quando há trabalho parado. Encerrar captura conclui a foto e a fila em andamento."},
   {"0","Indicadores e saúde","Os números da tela ao vivo mostram fotos, revisão e entrega; toque em um deles para abrir a tela. Toque no estado da câmera, no topo, para ver a Saúde do fluxo: câmera, bateria, espaço, edição e Fotto. O Tour do Lumo fica no menu."},
   {"1","Galeria e álbuns","A Galeria reúne fotos importadas e carrega mais itens conforme você rola. Toque em Álbum para filtrar pelas pastas de origem. Toque em uma foto para abrir o visualizador; segure para selecionar várias."},
   {"1","Editor manual","Nas opções de uma foto, abra Editar. Compare Original, Automático, Predefinição e Final. Selecione Exposição, Contraste, Realces, Sombras, Brancos, Pretos, Temperatura, Matiz, Vibração ou Saturação e ajuste a barra. O botão Ajuda no editor explica cada etapa na própria tela."},
   {"1","Edição em lote","Segure uma foto e selecione outras. Use Editar para abrir o grupo, Copiar para guardar os ajustes de uma foto e Colar para reaplicá-los. No editor, confira se está salvando na foto atual ou no grupo. O original continua preservado."},
   {"1","Compartilhar e excluir","Compartilhar originais selecionados abre os aplicativos de compartilhamento do Android. Excluir remove a seleção após confirmação. Excluir por filtros permite escolher situação e período; confira a quantidade antes de confirmar. A exclusão é real, inclusive durante a demonstração."},
   {"1","Preparação para galeria online","Exportar manifesto gera um arquivo JSON com os dados das fotos. Isso prepara uma integração futura; não publica uma galeria online nem envia as fotos por si só."},
   {"2","Curadoria e Revisão","Fotos sinalizadas pela curadoria aparecem aqui com nota e motivo. Confira cada imagem e aprove manualmente as boas para liberar a entrega. Você pode editar antes de aprovar. Uma nota técnica ajuda a decidir, mas não substitui sua avaliação."},
   {"3","Conta e evento Fotto","Na Entrega, o botão principal leva ao próximo passo: conectar a conta, escolher o evento de destino e enviar. O menu de três pontos reúne as demais opções. Atualize a lista se necessário; também existe a opção de informar o ID. Confira o evento antes de ativar qualquer envio."},
   {"3","Envio e confirmação","Envio direto automático acompanha novas fotos aprovadas. Enviar pendentes agora processa a fila existente. Acompanhe confirmadas, pendentes e erros: estar na fila não significa que a foto já chegou. Encerrar envio conclui a foto em andamento antes de parar."},
   {"3","Fotto Web e pasta de saída","Abrir Fotto no navegador é uma alternativa para conferir o evento ou usar o fluxo pelo site. Em Pasta produzida pelo Lumo, Alterar permite escolher onde salvar. Limpar pasta Editadas remove as cópias editadas após confirmação; leia o aviso antes de executar."},
   {"0","Pronto para começar","Crie ou selecione primeiro um evento; escolha a pasta de saída e o preset; depois conecte a câmera ou importe do cartão. Faça um teste com poucas fotos, revise o resultado e configure a Entrega. Você pode sair e repetir este tour quando quiser."}
  };
  if(step<0||step>=steps.length)return;
  boolean moving=tourDialog!=null&&tourDialog.isShowing();
  if(tourDialog!=null){Window old=tourDialog.getWindow();if(old!=null)old.setWindowAnimations(0);tourDialog.setOnCancelListener(null);tourDialog.dismiss();}
  int page=Integer.parseInt(steps[step][0]);select(page);
  if(page==1&&galleryList!=null)galleryList.setSelection(0);else scrollers[page].smoothScrollTo(0,0);
  final boolean last=step==steps.length-1;String[] where={"Tela ao vivo","Galeria","Revisão","Entrega"};
  LumoSheet sheet=new LumoSheet(this,steps[step][1],where[page]+" · passo "+(step+1)+" de "+steps.length,true);
  // Barra de progresso fina: mostra quanto do tour falta sem listar 18 pontos.
  FrameLayout track=new FrameLayout(this);track.setBackground(LumoTheme.fill(LumoTheme.LINE,dp(2)));View fill=new View(this);fill.setBackground(LumoTheme.gradient(dp(2)));
  int width=Math.min(dp(624),getResources().getDisplayMetrics().widthPixels-dp(48));track.addView(fill,new FrameLayout.LayoutParams(Math.max(dp(8),width*(step+1)/steps.length),-1));
  LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,dp(4));tp.setMargins(dp(12),dp(2),dp(12),dp(12));track.setLayoutParams(tp);sheet.view(track);
  sheet.text(steps[step][2]);
  sheet.action("Sair",LumoSheet.TEXT,this::finishTour);
  if(step>0)sheet.action("Voltar",LumoSheet.SECONDARY,()->showTour(step-1));
  sheet.action(last?"Concluir":"Próximo",LumoSheet.PRIMARY,()->{if(last)finishTour();else showTour(step+1);});
  sheet.keepOpen().onCancel(this::finishTour);sheet.cancelable(true);sheet.outsideCancels(false);
  if(moving){Window w=sheet.dialog.getWindow();if(w!=null)w.setWindowAnimations(0);}
  tourDialog=sheet.show();
 }
 void finishTour(){getSharedPreferences("hisho",0).edit().putBoolean("tourSeen",true).apply();if(tourDialog!=null){Dialog d=tourDialog;tourDialog=null;d.setOnCancelListener(null);if(d.isShowing())d.dismiss();}}

 void loadPreset(){try{String saved=getSharedPreferences("hisho",0).getString("preset",null);presetData=new JSONObject(saved==null?read(getAssets().open("vibrant.json")):saved);presetName=getSharedPreferences("hisho",0).getString("presetName","Vibrant");}catch(Exception e){presetData=new JSONObject();presetName="Vibrant";}}

 JSONObject buildCurrentSettings()throws Exception{
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);JSONObject cfg=new JSONObject();cfg.put("localEventId",database.activeEvent());cfg.put("auto",p.getBoolean("autoEnabled",true));cfg.put("autoStrength",WorkProfiles.autoStrength(MainActivity.this));cfg.put("edge",PhotoEditor.exportEdge(MainActivity.this));cfg.put("curation",p.getBoolean("curationEnabled",true));cfg.put("curationSensitivity",Math.max(0,Math.min(2,p.getInt("curationSensitivity",1))));cfg.put("backfill",p.getBoolean("backfillEnabled",true));cfg.put("presetName",p.getString("presetName",presetName));if(p.getBoolean("presetEnabled",true))cfg.put("preset",presetData);return cfg;
 }
 void publishLiveSettings(){try{loadPreset();JSONObject cfg=buildCurrentSettings();getSharedPreferences("hisho",0).edit().putString("liveSettings",cfg.toString()).apply();}catch(Exception ignored){}}
 void refreshQuickControls(){if(presetQuick!=null){boolean enabled=getSharedPreferences("hisho",0).getBoolean("presetEnabled",true);presetQuick.setText(enabled?"Predefinição: "+presetName:"Predefinição: desligada");}if(eventModeButton!=null){boolean on=getSharedPreferences("hisho",0).getBoolean("eventMode",false);eventModeButton.setText(on?"Modo rápido ativo":"Modo rápido");eventModeButton.setTextColor(on?SUCCESS:TEXT);if(settingsButton!=null)settingsButton.setVisibility(on?View.GONE:View.VISIBLE);}}
 void toggleEventMode(){if(!requireEvent())return;
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);boolean on=!p.getBoolean("eventMode",false);android.content.SharedPreferences.Editor e=p.edit().putBoolean("eventMode",on);
  if(on){e.putBoolean("backfillEnabled",true).putBoolean("curationEnabled",true).putBoolean("disconnectSound",true);if(!FottoApi.token(MainActivity.this).isEmpty()&&!p.getString("fottoGalleryId","").isEmpty())e.putBoolean("fottoAuto",true);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
  else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  e.apply();publishLiveSettings();refreshQuickControls();if(on){QueueRecovery.resume(MainActivity.this);FottoSync.kick(MainActivity.this);message("Modo rápido ativado.");}else message("Modo rápido desativado.");
 }
 void showPresetChooser(){
  ArrayList<String> names=PresetLibrary.names(MainActivity.this);boolean enabled=getSharedPreferences("hisho",0).getBoolean("presetEnabled",true);
  LumoSheet sheet=new LumoSheet(this,"Predefinição","Vale para as próximas fotos; as anteriores podem ser reeditadas na Galeria.");
  sheet.choice("close",LumoTheme.SECONDARY,"Sem predefinição","Só a edição automática",!enabled,()->{getSharedPreferences("hisho",0).edit().putBoolean("presetEnabled",false).apply();publishLiveSettings();refreshQuickControls();message("Predefinição desligada para as próximas fotos.");});
  if(!names.isEmpty())sheet.section("Suas predefinições");
  for(String name:names)sheet.choice("palette",CYAN,name,null,enabled&&name.equals(presetName),()->{JSONObject chosen=PresetLibrary.get(MainActivity.this,name);presetData=chosen;presetName=name;getSharedPreferences("hisho",0).edit().putString("preset",chosen.toString()).putString("presetName",name).putBoolean("presetEnabled",true).apply();publishLiveSettings();refreshQuickControls();message("Predefinição "+name+" aplicada às próximas fotos.");});
  sheet.section("Nova");
  sheet.item("add",CYAN,"Importar predefinição XMP","Arquivo exportado do Lightroom",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),10));
  sheet.show();
 }
 String age(long at){if(at<=0)return "—";long s=Math.max(0,(System.currentTimeMillis()-at)/1000);if(s<60)return "há "+s+"s";if(s<3600)return "há "+(s/60)+" min";if(s<48*3600)return "há "+(s/3600)+" h";return "há "+(s/86400)+" dias";}
 /** Primeiro número de um texto ("51%" → 51); -1 se não houver. */
 static int leadingInt(String v){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\d+").matcher(v==null?"":v);return m.find()?Integer.parseInt(m.group()):-1;}
 void showHealthPanel(){
  CaptureService cs=CaptureService.active;android.content.SharedPreferences p=getSharedPreferences("hisho",0);String gallery=p.getString("fottoGalleryId","");int errors=0;try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM jobs WHERE state IN ('error','originalError')",null)){if(c.moveToFirst())errors=c.getInt(0);}int q=database.pending(),reviews=database.reviewCount(),fottoPending=gallery.isEmpty()?0:database.fottoPending(gallery),confirmed=gallery.isEmpty()?0:database.fottoDone(gallery);String lastFotto=p.getString("fottoLastStatus","Sem atividade");
  boolean camOk=cs!=null&&cs.capturing;String camera=camOk?cs.cameraName:"Nenhuma conectada";boolean token=!FottoApi.token(MainActivity.this).isEmpty();String fottoError=gallery.isEmpty()?"":database.fottoLastError(gallery);
  String fotto=!token?"Não conectado":gallery.isEmpty()?"Sem evento":fottoError.isEmpty()?"OK":"Atenção";
  String batteryLine=metricBattery==null?"—":metricBattery.getText().toString().replace("BATERIA\n",""),storageLine=metricStorage==null?"—":metricStorage.getText().toString().replace("ESPAÇO\n","");
  int battery=leadingInt(batteryLine),freeGb=leadingInt(storageLine);boolean lowBattery=battery>=0&&battery<=20,lowSpace=freeGb>=0&&freeGb<1;
  boolean fottoProblem=lastFotto.startsWith("Falha")||lastFotto.toLowerCase(Locale.ROOT).contains("expirad")||!fottoError.isEmpty();
  int attention=(errors>0?1:0)+(reviews>0?1:0)+(lowBattery?1:0)+(lowSpace?1:0)+(fottoProblem||!"OK".equals(fotto)?1:0);
  LumoSheet sheet=new LumoSheet(this,"Saúde do fluxo",attention==0?"Tudo certo para fotografar.":attention==1?"1 ponto de atenção":attention+" pontos de atenção");
  if(fottoProblem){sheet.banner("warning",WARN,!fottoError.isEmpty()?fottoError:lastFotto);if(!token||lastFotto.toLowerCase(Locale.ROOT).contains("expirad"))sheet.item("person",CYAN,"Conectar Fotto novamente","Entre de novo para voltar a enviar",()->startActivityForResult(new Intent(MainActivity.this,FottoLoginActivity.class),30));}
  sheet.section("Aparelho")
   .status("camera",camOk?SUCCESS:LumoTheme.SECONDARY,"Câmera",camera)
   .status("battery",lowBattery?WARN:SUCCESS,"Bateria",batteryLine)
   .status("storage",lowSpace?WARN:SUCCESS,"Espaço livre",storageLine)
   .section("Fluxo")
   .status("download",errors==0?SUCCESS:WARN,"Recebimento",errors==0?"OK":errors==1?"1 erro":errors+" erros")
   .status("tune",q==0?SUCCESS:CYAN,"Edição",q==0?"Em dia":q+" na fila")
   .status("flag",reviews==0?SUCCESS:WARN,"Curadoria",reviews==0?"Nada para revisar":reviews==1?"1 para revisar":reviews+" para revisar")
   .status("clock",LumoTheme.SECONDARY,"Última foto recebida",age(Math.max(database.lastDownloadedAt(),p.getLong("lastPhotoAt",0))))
   .section("Fotto")
   .status("upload","OK".equals(fotto)?SUCCESS:WARN,"Envio direto",fotto)
   .status("check",LumoTheme.SECONDARY,"Fotos enviadas",confirmed+" confirmadas · "+fottoPending+" pendentes");
  if(!fottoProblem&&!lastFotto.isEmpty())sheet.status("info",LumoTheme.SECONDARY,"Último status",lastFotto);
  sheet.action("Retomar filas",LumoSheet.TEXT,()->{QueueRecovery.resume(MainActivity.this);message("Filas retomadas.");}).action("Fechar",LumoSheet.PRIMARY,null).show();
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
 void openEditor(String focusId,List<String> ids,boolean pasteOnOpen){
  ArrayList<String> selectedIds=new ArrayList<>();
  if(ids!=null)for(String id:ids)if(id!=null&&!id.trim().isEmpty()&&!selectedIds.contains(id))selectedIds.add(id);
  String primary=focusId==null?"":focusId.trim();
  if(primary.isEmpty()&&!selectedIds.isEmpty())primary=selectedIds.get(0);
  if(primary.isEmpty()){message("Selecione uma foto para editar.");return;}
  if(!selectedIds.contains(primary))selectedIds.add(0,primary);
  Intent editor=new Intent(MainActivity.this,ManualEditorActivity.class);
  editor.putExtra("focusId",primary);
  editor.putExtra("jobIds",android.text.TextUtils.join(",",selectedIds));
  editor.putExtra("pasteOnOpen",pasteOnOpen);
  try{startActivity(editor);}catch(Exception e){message("Não foi possível abrir o editor: "+e.getMessage());}
 }

 void select(int page){if(page<0||page>=4)page=0;selected=page;for(int i=0;i<4;i++){scrollers[i].setVisibility(i==page&&i!=1?View.VISIBLE:View.GONE);if(tabs[i]!=null)tabs[i].setTextColor(i==page?GREEN:MUTED);}if(galleryList!=null)galleryList.setVisibility(page==1?View.VISIBLE:View.GONE);if(page==1)refreshFiles(true);if(page==2)refreshReview(true);if(page==3)updateFottoStatus();}

 /** Tela "ao vivo": última foto em destaque, tira das recentes, três números e uma ação principal (igual ao iPhone). */
 void capturePage(){
  LinearLayout p=pages[0];p.setPadding(dp(14),dp(2),dp(14),dp(14));
  status=text("Pronto para conectar a câmera.",12,MUTED,false);status.setMaxLines(2);p.addView(status);
  timing=text("",11,LumoTheme.TERTIARY,false);timing.setVisibility(View.GONE);p.addView(timing);gap(p,8);

  int screen=getResources().getConfiguration().screenHeightDp;int previewH=Math.max(300,Math.min(640,(int)(screen*.52f)));
  previewFrame=new FrameLayout(MainActivity.this);previewFrame.setBackground(LumoTheme.card(0xff000000,dp(26),dp(1)));previewFrame.setClipToOutline(true);p.addView(previewFrame,new LinearLayout.LayoutParams(-1,dp(previewH)));
  preview=new ImageView(MainActivity.this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("Última foto. Toque para abrir.");previewFrame.addView(preview,new FrameLayout.LayoutParams(-1,-1));preview.setOnClickListener(v->openViewerFromLive());
  empty=vertical();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(24),dp(24),dp(24));previewFrame.addView(empty,new FrameLayout.LayoutParams(-1,-1));
  LumoMark ring=new LumoMark(MainActivity.this);empty.addView(ring,new LinearLayout.LayoutParams(dp(84),dp(84)));gap(empty,16);
  TextView wait=text("Pronto para o evento",21,Color.WHITE,true);wait.setGravity(Gravity.CENTER);empty.addView(wait);gap(empty,6);
  TextView note=text("Ligue a câmera pelo cabo USB (Wi-Fi da câmera desligado) e toque em Conectar. Cada foto aparece aqui, editada e avaliada.",14,MUTED,false);note.setGravity(Gravity.CENTER);note.setLineSpacing(dp(2),1);empty.addView(note);
  Button importLink=compactButton("Importar do cartão USB",v->{if(requireEvent())chooseCardFolder();});importLink.setTextSize(15);importLink.setTextColor(CYAN);importLink.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2222dff3),LumoTheme.card(0x1422dff3,dp(24),dp(1)),null));leadingIcon(importLink,"sdcard",CYAN);gap(empty,18);empty.addView(importLink,new LinearLayout.LayoutParams(-2,-2));
  LinearLayout footer=row();footer.setGravity(Gravity.BOTTOM);footer.setPadding(dp(14),dp(28),dp(14),dp(12));footer.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0xcc000000,0x00000000}));
  LinearLayout cap=vertical();previewTitle=text("",14,Color.WHITE,true);previewTitle.setPadding(dp(10),dp(4),dp(10),dp(4));previewTitle.setBackground(LumoTheme.fill(0x8c000000,dp(14)));cap.addView(previewTitle,new LinearLayout.LayoutParams(-2,-2));previewSub=text("",12,MUTED,false);previewSub.setSingleLine(true);previewSub.setPadding(0,dp(5),0,0);cap.addView(previewSub);footer.addView(cap,new LinearLayout.LayoutParams(0,-2,1));
  previewScore=text("",16,Color.WHITE,true);previewScore.setGravity(Gravity.CENTER);previewScore.setVisibility(View.GONE);footer.addView(previewScore,new LinearLayout.LayoutParams(dp(54),dp(54)));
  previewFooter=footer;footer.setVisibility(View.GONE);previewFrame.addView(footer,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
  liveChip=text("Voltar ao vivo",13,LumoTheme.ON_ACCENT,true);liveChip.setGravity(Gravity.CENTER);liveChip.setMinHeight(dp(40));liveChip.setPadding(dp(14),0,dp(14),0);liveChip.setBackground(LumoTheme.gradient(dp(16)));liveChip.setVisibility(View.GONE);liveChip.setOnClickListener(v->{gallery.followLatest();showSelection(true);});
  FrameLayout.LayoutParams lcp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);lcp.setMargins(dp(12),dp(12),0,0);previewFrame.addView(liveChip,lcp);

  gap(p,10);filmScroll=new HorizontalScrollView(MainActivity.this);filmScroll.setHorizontalScrollBarEnabled(false);filmStrip=row();filmScroll.addView(filmStrip);p.addView(filmScroll,new LinearLayout.LayoutParams(-1,dp(62)));
  // Navegação antiga da prévia: mantida fora da tela (o estado continua sendo usado pela seleção).
  previousPhoto=miniNav("‹",v->movePhoto(-1));nextPhoto=miniNav("›",v->movePhoto(1));livePhoto=miniNav("● Novas",v->{gallery.followLatest();showSelection(true);});galleryInfo=text("",11,MUTED,false);

  gap(p,10);LinearLayout tiles=row();
  tileFotos=statTile(tiles,"FOTOS",v->select(1),true);tileReview=statTile(tiles,"REVISÃO",v->select(2),false);tileFotto=statTile(tiles,"FOTTO",v->select(3),false);
  p.addView(tiles,new LinearLayout.LayoutParams(-1,-2));

  gap(p,10);LinearLayout act=row();
  connect=compactButton("Conectar câmera",v->{haptic(v);CaptureService s=CaptureService.active;if(s!=null){if(s.capturing)new AlertDialog.Builder(MainActivity.this).setTitle("Encerrar captura?").setMessage("A foto em andamento e as edições pendentes serão concluídas.").setPositiveButton("Encerrar",(d,n)->startService(new Intent(MainActivity.this,CaptureService.class).setAction("stop"))).setNegativeButton("Continuar",null).show();}else chooseTransport();});
  connect.setTextSize(17);connect.setTextColor(LumoTheme.ON_ACCENT);connect.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));connect.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),LumoTheme.gradient(dp(18)),null));act.addView(connect,new LinearLayout.LayoutParams(0,dp(58),1));
  ImageButton plus=iconButton("add","Mais: importar do cartão, Wi-Fi, predefinição e modo evento",TEXT,v->showAddMenu());plus.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),LumoTheme.card(CARD2,dp(18),dp(1)),null));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(58),dp(58));pp.leftMargin=dp(10);act.addView(plus,pp);
  p.addView(act);
  resume=compactButton("Retomar edições pendentes",v->start(null));resume.setVisibility(View.GONE);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(8);p.addView(resume,rp);
 }

 /** Cartão de número grande (FOTOS / REVISÃO / FOTTO). Devolve {valor, legenda}. */
 TextView[] statTile(LinearLayout parent,String title,View.OnClickListener l,boolean first){
  LinearLayout t=vertical();t.setPadding(dp(12),dp(10),dp(12),dp(10));t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),LumoTheme.card(CARD,dp(18),dp(1)),null));t.setOnClickListener(l);
  TextView label=text(title,10,LumoTheme.TERTIARY,true);label.setLetterSpacing(.12f);t.addView(label);
  TextView value=text("0",28,Color.WHITE,true);value.setTypeface(Typeface.create("sans-serif-black",Typeface.NORMAL));t.addView(value);
  TextView caption=text("",12,MUTED,false);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);t.addView(caption);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(!first)lp.leftMargin=dp(8);parent.addView(t,lp);
  return new TextView[]{value,caption};
 }

 /** Cabeçalho das telas secundárias com volta para a tela ao vivo. */
 TextView pageHeader(LinearLayout p,String title,String subtitle){
  LinearLayout back=row();back.setMinimumHeight(dp(48));back.setPadding(0,0,dp(14),0);back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2222dff3),null,LumoTheme.fill(Color.WHITE,dp(12))));back.setContentDescription("Voltar para a tela ao vivo");back.setOnClickListener(v->select(0));
  ImageView arrow=new ImageView(MainActivity.this);arrow.setImageDrawable(icon("back",CYAN));back.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
  back.addView(text("Ao vivo",15,CYAN,true));
  // O glifo do chevron começa ~7dp dentro do ícone: a margem negativa alinha a seta com o título.
  LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-2,-2);bl.leftMargin=-dp(7);p.addView(back,bl);
  TextView h=text(title,28,TEXT,true);p.addView(h);if(subtitle!=null&&!subtitle.isEmpty()){gap(p,4);label(p,subtitle,13);}gap(p,14);return h;
 }

 void showMainMenu(){
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);boolean event=p.getBoolean("eventMode",false),presetOn=p.getBoolean("presetEnabled",true);int reviews=database.reviewCount();String account=LumoSession.account(this);boolean lockOn=LumoLock.enabled(this);
  LumoSheet sheet=new LumoSheet(this,"Lumo",database.eventName())
   .section("Captura")
   .item("tune",CYAN,"Ajustes de captura e edição","Edição automática, resolução, curadoria e fotos anteriores",()->{if(CaptureService.active!=null)message("Encerre a captura para mudar os ajustes.");else captureSettingsDialog();})
   .item("palette",CYAN,"Predefinição",presetOn?presetName:"Desligada",this::showPresetChooser)
   .item("bolt",event?SUCCESS:CYAN,event?"Desligar modo evento":"Ligar modo evento",event?"Ligado · tela sempre acesa e envio automático":"Tela sempre acesa, curadoria e envio automático",this::toggleEventMode)
   .section("Telas")
   .item("photos",CYAN,"Galeria","Todas as fotos do evento, com filtros e álbuns",()->select(1))
   .item("flag",reviews>0?WARN:CYAN,"Revisão",reviews>0?(reviews==1?"1 foto para revisar":reviews+" fotos para revisar"):"Nada para revisar",()->select(2))
   .item("upload",CYAN,"Entrega Fotto","Conta, evento de destino e envio",()->select(3))
   .section("Ajuda")
   .item("heart",SUCCESS,"Saúde do fluxo","Câmera, bateria, espaço, filas e Fotto",this::showHealthPanel)
   .item("bell",CYAN,"Testar alarme de desconexão","Toca o alerta usado quando a câmera cai",()->DisconnectAlert.test(MainActivity.this))
   .item("help",CYAN,"Tour do Lumo","Rever a apresentação passo a passo",()->showTour(0))
   .item("warning",CrashReporter.pending(this)>0?WARN:CYAN,"Relatórios de falha",crashSubtitle(),this::showCrashReports)
   .section("Conta")
   .item("lock",lockOn?SUCCESS:CYAN,lockOn?"Desligar bloqueio com digital":"Bloquear com digital",lockOn?"Ligado · pede a digital ao abrir e após 5 min fora":"Pede a digital ao abrir o Lumo",this::toggleBiometricLock);
  // APK Demo sem conta: não há do que sair (a tela de entrada mandaria de volta para cá).
  if(!(DemoLicense.isDemo(this)&&FottoApi.token(this).isEmpty()))sheet.item("person",DANGER,LumoSession.demo(this)?"Sair do modo demonstração":"Sair da conta Fotto",account.isEmpty()?"Volta para a tela de entrada":account,this::confirmSignOut);
  sheet.show();
 }

 String crashSubtitle(){
  if(!CrashReporter.enabled(this))return "Envio desligado · o último relatório fica no aparelho";
  if(!CrashReporter.configured(this))return "Guardados no aparelho para compartilhar";
  int n=CrashReporter.pending(this);return n>0?(n==1?"1 relatório aguardando envio":n+" relatórios aguardando envio"):"Ligado · enviado quando o Lumo fecha por erro";
 }

 /** Relatórios de falha: o que é enviado, ligar/desligar, compartilhar o último e testar o envio. */
 void showCrashReports(){
  boolean on=CrashReporter.enabled(this),configured=CrashReporter.configured(this);java.io.File last=CrashReporter.lastReport(this);
  LumoSheet sheet=new LumoSheet(this,"Relatórios de falha",last.exists()?"Último: "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new java.util.Date(last.lastModified())):"Nenhuma falha registrada")
   .text("Quando o Lumo fecha por erro ou trava, ele guarda um relatório técnico: modelo do aparelho, versões do Android e do Lumo, onde o erro aconteceu e os últimos passos do app. Nunca entram fotos, a conta ou a senha do Fotto, nomes de eventos ou e-mails.");
  if(!configured)sheet.banner("info",CYAN,"Esta versão não envia relatórios sozinha. Use Compartilhar para mandar o último ao desenvolvedor.");
  else if(!on)sheet.banner("warning",WARN,"Envio desligado: os relatórios ficam só neste aparelho.");
  if(last.exists())sheet.item("share",CYAN,"Compartilhar último relatório","Texto para WhatsApp ou e-mail",()->{try{String text=CrashReporter.readAll(new java.io.FileInputStream(last),200*1024);
   startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Relatório de falha do Lumo").putExtra(Intent.EXTRA_TEXT,text),"Compartilhar relatório"));}catch(Exception e){message("Não foi possível abrir o relatório.");}});
  if(configured&&on)sheet.item("upload",CYAN,"Enviar relatório de teste","Confere se os relatórios estão chegando",()->{message("Enviando relatório de teste…");
   CrashReporter.sendTest(MainActivity.this,()->message(CrashReporter.pending(MainActivity.this)==0?"Relatório de teste enviado.":"Sem conexão com o servidor de relatórios. Ele será enviado depois."));});
  if(configured)sheet.item(on?"close":"check",on?DANGER:SUCCESS,on?"Desligar envio automático":"Ligar envio automático",on?"Os relatórios passam a ficar só no aparelho":"Envia na próxima abertura do Lumo",()->{CrashReporter.setEnabled(MainActivity.this,!on);message(on?"Envio de relatórios desligado.":"Envio de relatórios ligado.");});
  sheet.show();
 }

 /** Sair da conta: fotos, eventos e edições ficam no aparelho; a sessão Fotto, o evento de destino e o envio automático são apagados. */
 void confirmSignOut(){
  if(CaptureService.active!=null){message("Encerre a captura ou a importação antes de sair da conta.");return;}
  String gallery=getSharedPreferences("hisho",0).getString("fottoGalleryId","");int pending=gallery.isEmpty()?0:database.fottoPending(gallery);
  String account=LumoSession.account(this);
  String body=(account.isEmpty()?"":"Conta: "+account+"\n\n")+"Fotos, eventos e edições continuam neste aparelho. O envio automático para e o evento de destino do Fotto é esquecido. Para usar o Lumo de novo, entre com a conta Fotto."
   +(pending>0?"\n\n"+(pending==1?"1 foto ainda não foi enviada":pending+" fotos ainda não foram enviadas")+" ao Fotto. Elas ficam na fila e voltam a ser enviadas quando você entrar e escolher o mesmo evento.":"");
  new AlertDialog.Builder(this).setTitle(LumoSession.demo(this)?"Sair do modo demonstração?":"Sair da conta Fotto?").setMessage(body).setNegativeButton("Cancelar",null)
   .setPositiveButton("Sair",(d,n)->{LumoSession.signOut(MainActivity.this);boolean demoApk=DemoLicense.isDemo(MainActivity.this);if(demoApk)message("Você saiu da conta Fotto.");startActivity(new Intent(MainActivity.this,demoApk?MainActivity.class:LumoLoginActivity.class).putExtra("signedOut",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();}).show();
 }

 /** Liga/desliga o bloqueio com digital. Ligar pede a digital antes, para confirmar que funciona neste aparelho. */
 void toggleBiometricLock(){
  if(LumoLock.enabled(this)){LumoLock.setEnabled(this,false);message("Bloqueio com digital desligado.");return;}
  if(!LumoLock.available(this)){
   new AlertDialog.Builder(this).setTitle("Cadastre uma digital").setMessage("Para bloquear o Lumo, cadastre uma digital (ou um PIN/padrão de tela) nas configurações do Android e volte aqui.").setNegativeButton("Agora não",null)
    .setPositiveButton("Abrir configurações",(d,n)->{Intent i=Build.VERSION.SDK_INT>=30?new Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL).putExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK|android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL):new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
     try{startActivity(i);}catch(Exception e){try{startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));}catch(Exception ignored){message("Abra Configurações > Segurança e cadastre uma digital.");}}}).show();
   return;
  }
  LumoLock.prompt(this,"Ligar bloqueio com digital","Confirme sua digital para ligar",(ok,code,msg)->{
   if(destroyed)return;
   if(ok){LumoLock.setEnabled(MainActivity.this,true);message("Bloqueio ligado. O Lumo pede a digital ao abrir e depois de 5 minutos fora do app.");}
   else if(code!=android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED&&code!=android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED)message("Bloqueio não ligado: "+(msg==null?"digital não confirmada":msg));
  });
 }

 void showAddMenu(){
  if(!requireEvent())return;
  android.content.SharedPreferences p=getSharedPreferences("hisho",0);boolean event=p.getBoolean("eventMode",false),presetOn=p.getBoolean("presetEnabled",true);
  new LumoSheet(this,"Adicionar",database.eventName())
   .item("sdcard",CYAN,"Importar do cartão USB ou pasta","Procura nas subpastas e ignora fotos já importadas",this::chooseCardFolder)
   .item("wifi",CYAN,"Conectar por Wi-Fi","Experimental · conecte o aparelho à rede da câmera",this::wifiDialog)
   .item("palette",CYAN,"Predefinição",presetOn?presetName:"Desligada",this::showPresetChooser)
   .item("bolt",event?SUCCESS:CYAN,event?"Desligar modo evento":"Ligar modo evento",event?"Ligado":"Tela sempre acesa, curadoria e envio automático",this::toggleEventMode)
   .item("photos",CYAN,"Galeria",null,()->select(1))
   .show();
 }

 /** Abre o visualizador a partir da prévia ao vivo, com todas as fotos do evento. */
 void openViewerFromLive(){
  ArrayList<String> ids=new ArrayList<>();String start="";
  try(Cursor c=database.getReadableDatabase().rawQuery("SELECT id,edited FROM jobs WHERE original IS NOT NULL AND "+database.eventSql()+" ORDER BY rowid DESC LIMIT 2000",null)){while(c.moveToNext()){ids.add(c.getString(0));if(start.isEmpty()&&shownUri.equals(c.getString(1)))start=c.getString(0);}}
  if(ids.isEmpty())return;openViewer(ids,start.isEmpty()?ids.get(0):start);
 }

 void openViewer(List<String> ids,String start){
  if(ids==null||ids.isEmpty())return;
  Intent v=new Intent(MainActivity.this,PhotoViewerActivity.class).putExtra(PhotoViewerActivity.EXTRA_IDS,ids.toArray(new String[0])).putExtra(PhotoViewerActivity.EXTRA_START,start);
  try{startActivityForResult(v,REQUEST_VIEWER);}catch(Exception e){message("Não foi possível abrir a foto: "+e.getMessage());}
 }

 /** Modo de demonstração (capturas de tela no emulador): evento + fotos sintéticas pela fila real de edição. */
 void demoSeed(){
  // A Activity pode ter sido recriada (mudança de configuração) antes deste post rodar: a instância antiga já encerrou os executores.
  if(destroyed||isFinishing()||images.isShutdown())return;
  String name="Casamento Ana & Pedro";
  if(!name.equals(database.eventName())){try{String id=database.createEvent(name);selectLocalEvent(id);}catch(Exception e){message("Demo: "+e.getMessage());return;}}
  final String event=database.activeEvent();final String settings;try{settings=buildCurrentSettings().toString();}catch(Exception e){return;}
  if(CaptureService.active==null)try{startForegroundService(new Intent(this,CaptureService.class).setAction("queue_import").putExtra("settings",settings));}catch(Exception e){message("Demo: "+e.getMessage());return;}
  images.execute(()->{
   File spool=new File(getFilesDir(),"queue");spool.mkdirs();
   for(int i=0;i<DemoScenes.count();i++){try{String id=sha256Text("demo:"+event+":"+i);if(database.exists(id))continue;File temp=new File(spool,id+".jpg");DemoScenes.render(i,temp);
    String outName=String.format(Locale.ROOT,"IMG_%04d_%s.jpg",4810+i,id.substring(0,10));String hash=PhotoHash.sha256(temp);String pHash="";try{pHash=PhotoHash.perceptualHash(temp);}catch(Exception ignored){}
    database.addImported(id,temp,outName,settings,System.currentTimeMillis()-(DemoScenes.count()-i)*37000L,hash,pHash,temp.length());database.setAlbum(id,"Demonstração",outName);
    Uri original=database.save(temp,"Originais",outName,id,"original");database.set(id,"ready","original",original.toString());}catch(Exception ignored){}}
   try{startService(new Intent(MainActivity.this,CaptureService.class).setAction("finish_import"));}catch(Exception ignored){}
   handler.post(()->{gallerySignature="";filesSignature="";reviewSignature="";refreshGallery();});
  });
 }

 @Override public void onBackPressed(){if(selected!=0){select(0);return;}super.onBackPressed();}

 Button miniNav(String title,View.OnClickListener l){Button b=compactButton(title,l);b.setTextSize(11);return b;}

 void showAlbumChooser(){ArrayList<String> list=database.albums();LumoSheet sheet=new LumoSheet(this,"Álbum","Filtra a galeria pela pasta de origem das fotos.");sheet.choice("photos",CYAN,"Todos os álbuns",null,galleryAlbum.isEmpty(),()->pickAlbum(""));for(String name:list)sheet.choice("folder",CYAN,name,null,name.equals(galleryAlbum),()->pickAlbum(name));sheet.show();}
 void pickAlbum(String name){galleryAlbum=name;albumButton.setText("Álbum: "+(name.isEmpty()?"todos":name));resetGalleryAlbum();refreshFiles(true);}
 void showGalleryMore(){int n=editSelection.size();new LumoSheet(this,"Galeria",n==0?"Segure uma foto para selecionar várias.":(n==1?"1 foto selecionada":n+" fotos selecionadas"))
  .item("share",CYAN,"Compartilhar originais",n==0?"Selecione fotos primeiro":"Abre os apps de compartilhamento com os originais",this::shareOriginals)
  .item("file",CYAN,"Exportar manifesto","JSON para a futura galeria online; não envia fotos",()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"lumo-gallery-manifest.json"),41))
  .item("delete",DANGER,"Excluir por filtros","Escolha situação e período; pede confirmação",this::showDeleteFilters)
  .show();}
 void galleryPage(){
  LinearLayout p=pages[1];pageHeader(p,"Galeria","Toque para abrir, deslizar e dar zoom. Segure para selecionar várias.");
  HorizontalScrollView filters=new HorizontalScrollView(this);filters.setHorizontalScrollBarEnabled(false);LinearLayout chips=row();filters.addView(chips);
  for(int i=0;i<FILTERS.length;i++){final int f=i;TextView c=text(FILTERS[i],14,TEXT,true);c.setGravity(Gravity.CENTER);c.setMinHeight(dp(40));c.setPadding(dp(16),0,dp(16),0);c.setCompoundDrawablePadding(dp(6));c.setOnClickListener(v->{galleryFilter=f;styleFilterChips();resetGalleryAlbum();refreshFiles(true);});LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.rightMargin=dp(8);chips.addView(c,cl);filterChips[i]=c;}
  styleFilterChips();p.addView(filters,new LinearLayout.LayoutParams(-1,-2));gap(p,8);
  albumButton=compactButton("Álbum: todos",v->showAlbumChooser());albumButton.setCompoundDrawablesRelative(null,null,icon("expand",LumoTheme.SECONDARY),null);LinearLayout galleryTools=row();galleryTools.addView(albumButton,new LinearLayout.LayoutParams(0,-2,1));ImageButton more=iconButton("more","Mais ações da galeria",TEXT,v->showGalleryMore());more.setBackground(new RippleDrawable(ColorStateList.valueOf(0x180b7cff),border(CARD2,12),null));LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(dp(52),dp(48));mlp.leftMargin=dp(8);galleryTools.addView(more,mlp);p.addView(galleryTools);


  galleryPageLabel=text("",11,MUTED,false);p.addView(galleryPageLabel);

  gallerySelectionLabel=text("",13,CYAN,true);gallerySelectionLabel.setVisibility(View.GONE);p.addView(gallerySelectionLabel);gap(p,8);
  galleryActionBar=vertical();galleryActionBar.setPadding(dp(4),dp(4),dp(4),dp(4));galleryActionBar.setBackground(border(CARD,14));String[][] actionRows={{"Favoritar","Aprovar","Enviar","Excluir"},{"Editar","Copiar","Colar"}};for(String[] actions:actionRows){LinearLayout line=row();for(String a:actions){Button b=miniNav(a,v->{haptic(v);String x=((Button)v).getText().toString();if("Editar".equals(x))editSelected();else if("Copiar".equals(x))copyGalleryAdjustments();else if("Colar".equals(x))pasteGalleryAdjustments();else if("Aprovar".equals(x))approveSelected();else if("Favoritar".equals(x))favoriteSelected();else if("Enviar".equals(x))sendSelected();else confirmPhotoDeletion(new ArrayList<>(editSelection),"seleção");});if("Excluir".equals(a))b.setTextColor(DANGER);b.setTextSize(13);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));line.addView(b,lp);}galleryActionBar.addView(line);}galleryActionBar.setVisibility(View.GONE);p.addView(galleryActionBar);gap(p,9);
  clearSelection=compactButton("Limpar seleção",v->{editSelection.clear();selectionMode=false;refreshFiles(true);});clearSelection.setVisibility(View.GONE);p.addView(clearSelection,new LinearLayout.LayoutParams(-1,-2));
  galleryEmpty=emptyState("photos",LumoTheme.SECONDARY,"Nenhuma foto neste evento","Conecte a câmera ou importe do cartão. As fotos aparecem aqui assim que chegam, já editadas.","Importar do cartão USB",v->{if(requireEvent())chooseCardFolder();},"Conectar câmera",v->{select(0);chooseTransport();});galleryEmpty.setVisibility(View.GONE);p.addView(galleryEmpty);
  galleryList=new ListView(this);galleryList.setDivider(null);galleryList.setDividerHeight(0);galleryList.setBackgroundColor(BG);
  scrollers[1].removeView(p);galleryList.addHeaderView(p,null,false);
  LinearLayout footer=row();footer.setGravity(Gravity.CENTER);footer.setPadding(dp(12),dp(12),dp(12),dp(12));gallerySpinner=new ProgressBar(this);footer.addView(gallerySpinner,new LinearLayout.LayoutParams(dp(24),dp(24)));galleryLoadingLabel=text("",12,MUTED,false);galleryLoadingLabel.setPadding(dp(10),0,0,0);footer.addView(galleryLoadingLabel);galleryList.addFooterView(footer,null,false);footer.setOnClickListener(v->loadMoreGallery());
  galleryAdapter=new BaseAdapter(){public int getCount(){int cols=galleryColumns();return (galleryLoaded.size()+cols-1)/cols;}public Object getItem(int pos){return pos;}public long getItemId(int pos){return pos;}public View getView(int pos,View old,ViewGroup parent){LinearLayout line=old instanceof LinearLayout?(LinearLayout)old:row();line.removeAllViews();line.setPadding(dp(11),0,dp(11),0);int cols=galleryColumns();int cell=(getResources().getDisplayMetrics().widthPixels-dp(28)-dp((cols-1)*6))/cols;for(int col=0;col<cols;col++){int index=pos*cols+col;if(index>=galleryLoaded.size())break;PhotoItem item=galleryLoaded.get(index);ensureLegacyScore(item);View tile=galleryTile(item,cell);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(cell,cell+dp(31));lp.setMargins(dp(3),dp(3),dp(3),dp(5));line.addView(tile,lp);}return line;}};
  galleryList.setAdapter(galleryAdapter);((FrameLayout)scrollers[1].getParent()).addView(galleryList,new FrameLayout.LayoutParams(-1,-1));galleryList.setVisibility(View.GONE);
  galleryList.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int state){}public void onScroll(AbsListView view,int first,int count,int total){if(count>0&&first+count>=total-4)loadMoreGallery();}});

 }

 void reviewPage(){
  LinearLayout p=pages[2];reviewHeading=pageHeader(p,"Revisão (0)","Fotos que a curadoria sinalizou, com nota técnica de 1 a 10. Aprove as boas para liberar a entrega.");reviewFiles=vertical();p.addView(reviewFiles);
 }

 void fottoPage(){
  LinearLayout p=pages[3];pageHeader(p,"Entrega","Envio direto para o evento no Fotto, com confirmação de cada foto.");
  LinearLayout sync=panel(p);
  LinearLayout head=row();fottoStatus=text("Fotto • Não conectado",18,TEXT,true);head.addView(fottoStatus,new LinearLayout.LayoutParams(0,-2,1));
  ImageButton deliverOptions=iconButton("more","Opções da entrega: conta, evento, pasta",TEXT,v->deliverMenu());deliverOptions.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),LumoTheme.fill(CARD2,dp(24)),null));head.addView(deliverOptions,new LinearLayout.LayoutParams(dp(48),dp(48)));sync.addView(head);
  gap(sync,5);fottoEventLabel=label(sync,"Evento: —",13);gap(sync,4);fottoUploadStatus=label(sync,"0 confirmadas · 0 na fila",13);gap(sync,10);
  LinearLayout monitor=row();LinearLayout copy=vertical();copy.addView(text("Envio direto automático",14,TEXT,true));TextView autoHint=text("Cada foto aprovada é enviada e confirmada no evento.",12,MUTED,false);autoHint.setPadding(0,dp(2),dp(8),0);copy.addView(autoHint);monitor.addView(copy,new LinearLayout.LayoutParams(0,-2,1));Switch sw=new Switch(MainActivity.this);fottoAuto=sw;sw.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{CYAN,0xffc5ccd6}));sw.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0x8822dff3,0x40ffffff}));sw.setMinimumHeight(dp(48));sw.setContentDescription("Envio direto automático");monitor.addView(sw);sync.addView(monitor);
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);sw.setChecked(prefs.getBoolean("fottoAuto",false));bindFottoToggle();
  gap(sync,9);fottoSend=compactButton("Enviar pendentes agora",v->{haptic(v);fottoPrimaryAction();});fottoSend.setTextSize(15);primary(fottoSend);sync.addView(fottoSend,new LinearLayout.LayoutParams(-1,-2));
  gap(sync,7);Button stopSend=compactButton("Encerrar envio",v->{haptic(v);FottoSync.stop(MainActivity.this);if(fottoAuto!=null){fottoAuto.setOnCheckedChangeListener(null);fottoAuto.setChecked(false);bindFottoToggle();}updateFottoStatus();message(FottoSync.running()?"Envio será encerrado assim que a foto atual terminar.":"Envio encerrado.");});stopSend.setTextColor(DANGER);fottoStop=stopSend;sync.addView(stopSend,new LinearLayout.LayoutParams(-1,-2));
  gap(sync,7);Button web=compactButton("Abrir Fotto no navegador",v->openFottoWeb());sync.addView(web,new LinearLayout.LayoutParams(-1,-2));
  gap(sync,5);label(sync,"O navegador serve para conferir o evento ou como alternativa se o envio direto falhar.",12);
  gap(p,10);fottoProblems=text("",12,DANGER,false);fottoProblems.setPadding(dp(12),dp(10),dp(12),dp(10));fottoProblems.setBackground(border(darkMode?0xff2a1717:0xfffff6f6,12));fottoProblems.setVisibility(View.GONE);p.addView(fottoProblems);
  gap(p,10);LinearLayout storage=panel(p);LinearLayout sr=row();LinearLayout st=vertical();st.addView(text("Pasta produzida pelo Lumo",14,TEXT,true));folderLabel=text("",12,MUTED,false);st.addView(folderLabel);sr.addView(st,new LinearLayout.LayoutParams(0,-2,1));folderButton=compactButton("Alterar",v->chooseExportFolder());sr.addView(folderButton,new LinearLayout.LayoutParams(-2,-2));storage.addView(sr);
  gap(storage,8);LinearLayout cleanRow=row();Button cleanEdited=compactButton("Limpar pasta Editadas",v->confirmClearEdited());cleanEdited.setTextColor(DANGER);cleanRow.addView(cleanEdited,new LinearLayout.LayoutParams(-1,-2));storage.addView(cleanRow);
  gap(storage,4);label(storage,"Remove somente as cópias editadas. Originais, notas, histórico e fotos em Revisão são preservados.",12);updateExportFolder();
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
  flowStatus.setText(cam+" • "+conn);flowStatus.setTextColor("CONECTADA".equals(conn)?SUCCESS:("Sem câmera".equals(conn)?MUTED:WARN));
  if(metricBattery!=null){metricBattery.setText("BATERIA\n"+b);metricBattery.setTextColor(lowBattery?WARN:TEXT);}
  if(metricStorage!=null){metricStorage.setText("ESPAÇO\n"+gb+" GB");metricStorage.setTextColor(lowSpace?WARN:TEXT);}
  if(metricPending!=null){metricPending.setText("PENDENTES\n"+editing);metricPending.setTextColor(editing>0?WARN:MUTED);}
  if(metricProcessed!=null){metricProcessed.setText("PROCESSADAS\n"+processed);metricProcessed.setTextColor(processed>0?TEXT:MUTED);}
  if(metricSent!=null){metricSent.setText("ENVIADAS\n"+sent);metricSent.setTextColor(sent>0?SUCCESS:MUTED);}
  if(fottoLiveStatus!=null){String state=FottoApi.token(MainActivity.this).isEmpty()?"NÃO CONECTADO":gallery.isEmpty()?"SEM EVENTO":directActive?"ATIVO":"PAUSADO";if(FottoSync.running())state="ENVIANDO";String title=prefs.getString("fottoGalleryTitle","");String line1="Fotto direto • "+state+(title.isEmpty()?"":" · "+title);String line2="Enviadas "+sent+" · confirmadas "+confirmed+" · processando "+processing+" · fila "+pending;String last=prefs.getString("fottoLastStatus","");if(!last.isEmpty()&&last.length()<150)line2+="\n"+last;fottoLiveStatus.setText(line1+"\n"+line2);fottoLiveStatus.setTextColor(!fErr.isEmpty()?WARN:(directActive?SUCCESS:MUTED));}
  if(prefs.getBoolean("eventMode",false)){if(lowBattery)EventAlert.signal(MainActivity.this,"bateria","Bateria em "+battery+"%. Conecte o carregador.");if(lowSpace)EventAlert.signal(MainActivity.this,"espaco","Menos de 1 GB livre. Libere armazenamento.");if(!fErr.isEmpty())EventAlert.signal(MainActivity.this,"fotto","Falha na entrega Fotto: "+fErr);}
  int reviews=database.reviewCount();if(tabs[2]!=null)tabs[2].setText(reviews>0?"Revisão ("+reviews+")":"Revisão");
  if(tileFotos!=null){
   int total=0,approved=0;try(Cursor q=database.getReadableDatabase().rawQuery("SELECT COUNT(*),SUM(CASE WHEN state='done' THEN 1 ELSE 0 END) FROM jobs WHERE original IS NOT NULL AND "+database.eventSql(),null)){if(q.moveToFirst()){total=q.getInt(0);approved=q.getInt(1);}}
   tileFotos[0].setText(String.valueOf(total));tileFotos[1].setText(editing>0?"editando "+editing:approved+" aprovadas");
   tileReview[0].setText(String.valueOf(reviews));tileReview[0].setTextColor(reviews>0?WARN:Color.WHITE);tileReview[1].setText(reviews>0?"toque para ver":"tudo certo");
   boolean token=!FottoApi.token(MainActivity.this).isEmpty();
   tileFotto[0].setText(gallery.isEmpty()?"—":String.valueOf(confirmed));tileFotto[0].setTextColor(!fErr.isEmpty()?DANGER:FottoSync.running()?CYAN:Color.WHITE);
   tileFotto[1].setText(!token?"conectar":gallery.isEmpty()?"escolher destino":!fErr.isEmpty()?"atenção":FottoSync.running()?"enviando…":pending>0?pending+" na fila":directActive?"automático":"confirmadas");
  }
  if(badge!=null){boolean live="CONECTADA".equals(conn);badge.setBackground(LumoTheme.card(live?0x2633db85:CARD,dp(24),dp(1)));}
 }

 void captureSettingsDialog(){
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);ScrollView scroll=new ScrollView(MainActivity.this);LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(12));scroll.addView(box);

  box.addView(text("Perfil de trabalho",13,TEXT,true));Spinner profile=new Spinner(MainActivity.this);profile.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,WorkProfiles.NAMES));String active=prefs.getString("workProfile","Personalizado");int activeIndex=0;for(int i=0;i<WorkProfiles.NAMES.length;i++)if(WorkProfiles.NAMES[i].equals(active))activeIndex=i;profile.setSelection(activeIndex);final boolean[] firstProfile={true};profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(firstProfile[0]){firstProfile[0]=false;return;}String name=WorkProfiles.NAMES[pos];WorkProfiles.apply(MainActivity.this,name);loadPreset();publishLiveSettings();refreshQuickControls();message("Perfil "+name+" aplicado.");}public void onNothingSelected(android.widget.AdapterView<?> p){}});box.addView(profile,new LinearLayout.LayoutParams(-1,dp(48)));

  TextView pn=text("Predefinição · "+presetName,15,TEXT,true);box.addView(pn);gap(box,8);
  Button choosePreset=compactButton("Trocar predefinição agora",v->showPresetChooser());box.addView(choosePreset,new LinearLayout.LayoutParams(-1,dp(42)));gap(box,5);

  Switch auto=settingSwitch(box,"Correção automática",prefs.getBoolean("autoEnabled",true));auto.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("autoEnabled",c).apply();publishLiveSettings();});
  box.addView(text("Intensidade da correção automática",13,TEXT,true));SeekBar strength=new SeekBar(MainActivity.this);strength.setMax(150);strength.setProgress((int)Math.round(WorkProfiles.autoStrength(MainActivity.this)*100));TextView strengthLabel=text(String.format(Locale.ROOT,"%d%%",strength.getProgress()),11,MUTED,false);box.addView(strengthLabel);strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){strengthLabel.setText(value+"%");prefs.edit().putLong("autoStrengthBits",Double.doubleToLongBits(value/100.0)).apply();if(user)publishLiveSettings();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){publishLiveSettings();}});box.addView(strength,new LinearLayout.LayoutParams(-1,dp(40)));

  Switch preset=settingSwitch(box,"Aplicar predefinição",prefs.getBoolean("presetEnabled",true));preset.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("presetEnabled",c).apply();publishLiveSettings();refreshQuickControls();});
  gap(box,8);box.addView(text("Resolução da cópia editada",13,TEXT,true));Spinner resolution=new Spinner(MainActivity.this);String[] modes={"Resolução original (recomendado)","Reduzida · 2560 px","Reduzida · 1920 px"};resolution.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,modes));resolution.setSelection(Math.max(0,Math.min(2,prefs.getInt("exportResolution",0))));resolution.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("exportResolution",pos).apply();publishLiveSettings();}public void onNothingSelected(android.widget.AdapterView<?> p){}});box.addView(resolution,new LinearLayout.LayoutParams(-1,dp(48)));

  Switch curate=settingSwitch(box,"Curadoria automática",prefs.getBoolean("curationEnabled",true));curate.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("curationEnabled",c).apply();publishLiveSettings();});box.addView(text("Sensibilidade da curadoria",13,TEXT,true));Spinner sensitivity=new Spinner(MainActivity.this);String[] cs={"Baixa","Média","Alta"};sensitivity.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,cs));sensitivity.setSelection(Math.max(0,Math.min(2,prefs.getInt("curationSensitivity",1))));sensitivity.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("curationSensitivity",pos).apply();publishLiveSettings();}public void onNothingSelected(android.widget.AdapterView<?> p){}});box.addView(sensitivity,new LinearLayout.LayoutParams(-1,dp(48)));

  Switch backfill=settingSwitch(box,"Recuperar fotos não baixadas ao reconectar",prefs.getBoolean("backfillEnabled",true));backfill.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean("backfillEnabled",c).apply();publishLiveSettings();});
  Switch sound=settingSwitch(box,"Som ao desconectar",prefs.getBoolean("disconnectSound",true));sound.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("disconnectSound",c).apply());

  Button saveProfile=compactButton("Salvar ajustes neste perfil",v->{String name=prefs.getString("workProfile","Personalizado");WorkProfiles.saveCurrent(MainActivity.this,name);message("Perfil "+name+" atualizado.");});box.addView(saveProfile,new LinearLayout.LayoutParams(-1,dp(42)));gap(box,5);
  Button importPreset=compactButton("Importar predefinição XMP",v->{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),10);});box.addView(importPreset,new LinearLayout.LayoutParams(-1,dp(44)));
  new AlertDialog.Builder(MainActivity.this).setTitle("Configurações da sessão").setView(scroll).setPositiveButton("Fechar",(d,n)->{publishLiveSettings();refreshQuickControls();}).show();
 }
 Switch settingSwitch(LinearLayout parent,String title,boolean checked){LinearLayout r=row();TextView t=text(title,14,TEXT,false);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));Switch s=new Switch(MainActivity.this);s.setChecked(checked);r.addView(s);parent.addView(r,new LinearLayout.LayoutParams(-1,dp(48)));return s;}

 static final class PhotoItem{String id,name,uri,state,reason,fottoState="";int score,burstCount;boolean sent,favorite;long rowKey;PhotoItem(String i,String n,String u,String st,String r,int sc,boolean se,int bc){id=i;name=n;uri=u;state=st;reason=r;score=sc;sent=se;burstCount=bc;}}
 String albumWhere(){return " AND "+database.eventSql()+(galleryAlbum.isEmpty()?"":" AND album=?");}
 String[] albumArgs(){return galleryAlbum.isEmpty()?null:new String[]{galleryAlbum};}
 int galleryTotal(){try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM jobs WHERE original IS NOT NULL"+albumWhere(),albumArgs())){return c.moveToFirst()?c.getInt(0):0;}}
 static final String[] FILTERS={"Todas","Favoritas","Aprovadas","Revisão","Não enviadas","Enviadas"};
 private volatile int galleryFilter=0;
 private final TextView[] filterChips=new TextView[FILTERS.length];
 void styleFilterChips(){for(int i=0;i<filterChips.length;i++){TextView c=filterChips[i];if(c==null)continue;boolean on=i==galleryFilter;c.setSelected(on);c.setTextColor(on?LumoTheme.ON_ACCENT:Color.WHITE);c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),on?LumoTheme.fill(CYAN,dp(20)):LumoTheme.card(CARD,dp(20),dp(1)),null));c.setCompoundDrawablesRelative(on?LumoTheme.icon(MainActivity.this,"check",LumoTheme.ON_ACCENT,dp(18)):null,null,null,null);c.setContentDescription(FILTERS[i]+(on?", filtro selecionado":", filtro"));}}
 /** Condição SQL do filtro da galeria (alias j). */
 String filterSql(){switch(galleryFilter){case 1:return " AND j.id IN (SELECT id FROM favorites)";case 2:return " AND j.state='done'";case 3:return " AND j.state='review'";case 4:return " AND j.state='done' AND j.id NOT IN (SELECT job_id FROM fotto_uploads WHERE state IN ('uploaded','unconfirmed','confirmed'))";case 5:return " AND j.id IN (SELECT job_id FROM fotto_uploads WHERE state IN ('uploaded','unconfirmed','confirmed'))";default:return "";}}
 ArrayList<PhotoItem> queryGallery(String album,int limit,long before,String event){ArrayList<PhotoItem> out=new ArrayList<>();String where="j.original IS NOT NULL AND j.event_id="+android.database.DatabaseUtils.sqlEscapeString(event)+filterSql();ArrayList<String> args=new ArrayList<>();if(!album.isEmpty()){where+=" AND j.album=?";args.add(album);}if(before>0){where+=" AND j.rowid<?";args.add(String.valueOf(before));}String sql="SELECT j.id,COALESCE(j.source_name,j.name),COALESCE(j.edited,j.original),j.state,COALESCE(j.quality_note,j.error,''),j.score,(SELECT COUNT(*) FROM jobs b WHERE b.burst_id=j.burst_id),j.rowid,(SELECT COUNT(*) FROM favorites f WHERE f.id=j.id) FROM jobs j WHERE "+where+" ORDER BY j.rowid DESC LIMIT "+limit;try(Cursor c=database.getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){while(c.moveToNext()){PhotoItem item=new PhotoItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),false,c.getInt(6));item.rowKey=c.getLong(7);item.favorite=c.getInt(8)>0;out.add(item);}}return out;}
 void ensureLegacyScore(PhotoItem item){if(item==null||item.score>0||item.uri==null||item.uri.isEmpty()||!("done".equals(item.state)||"review".equals(item.state))||!scoringIds.add(item.id))return;final String id=item.id,uri=item.uri;scoring.execute(()->{File tmp=new File(getCacheDir(),"score_"+id+".jpg");try{try(InputStream in=getContentResolver().openInputStream(Uri.parse(uri));OutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("Foto indisponível");Jobs.copy(in,out);}int sensitivity=Math.max(0,Math.min(2,getSharedPreferences("hisho",0).getInt("curationSensitivity",1)));SmartCurator.Result q=SmartCurator.analyze(tmp,sensitivity,database,id);database.setIntelligence(id,q.pHash,q.duplicateOf,q.semantic);database.setQuality(id,q.score,q.reason+" · nota recalculada");handler.post(()->{filesSignature="";gallerySignature="";reviewSignature="";if(selected==1)refreshFiles(true);if(selected==2)refreshReview(true);});}catch(Exception ignored){}finally{tmp.delete();scoringIds.remove(id);}});}

 int galleryColumns(){return getResources().getConfiguration().screenWidthDp>=700?4:3;}
 void resetGalleryAlbum(){galleryGeneration++;galleryLoading=false;galleryLoaded.clear();galleryKnownTotal=0;filesSignature="";if(galleryAdapter!=null)galleryAdapter.notifyDataSetChanged();if(galleryList!=null)galleryList.setSelection(0);}
 String galleryFileSignature(List<PhotoItem> items){StringBuilder sig=new StringBuilder();for(PhotoItem x:items)sig.append(x.id).append(':').append(x.uri).append(':').append(x.state).append(':').append(x.score).append(';');return sig.toString();}
 void updateGalleryLoading(){if(gallerySpinner==null)return;gallerySpinner.setVisibility(galleryLoading?View.VISIBLE:View.GONE);galleryPageLabel.setText(galleryLoaded.size()+" / "+galleryKnownTotal+" fotos");galleryLoadingLabel.setText(galleryLoading?"Carregando fotos…":galleryLoaded.isEmpty()?"":galleryLoaded.size()<galleryKnownTotal?"Desça para carregar mais":"Todas as fotos carregadas");updateGalleryEmpty();}
 /** Estado vazio da galeria: com filtro/álbum ativo oferece "Mostrar todas"; sem fotos, importar ou conectar. */
 void updateGalleryEmpty(){if(galleryEmpty==null)return;boolean show=!galleryLoading&&galleryLoaded.isEmpty();galleryEmpty.setVisibility(show?View.VISIBLE:View.GONE);if(!show)return;boolean filtered=galleryFilter!=0||!galleryAlbum.isEmpty();
  TextView title=(TextView)galleryEmpty.getTag(R_TITLE),body=(TextView)galleryEmpty.getTag(R_BODY);Button main=(Button)galleryEmpty.getTag(R_MAIN);Button second=(Button)galleryEmpty.getTag(R_SECOND);
  if(filtered){title.setText("Nada neste filtro");body.setText("Nenhuma foto combina com "+(galleryAlbum.isEmpty()?"\u201c"+FILTERS[galleryFilter]+"\u201d":"o álbum \u201c"+galleryAlbum+"\u201d")+". Volte para todas as fotos do evento.");main.setText("Mostrar todas");main.setOnClickListener(v->{galleryFilter=0;galleryAlbum="";if(albumButton!=null)albumButton.setText("Álbum: todos");styleFilterChips();resetGalleryAlbum();refreshFiles(true);});second.setVisibility(View.GONE);}
  else{title.setText("Nenhuma foto neste evento");body.setText("Conecte a câmera ou importe do cartão. As fotos aparecem aqui assim que chegam, já editadas.");main.setText("Importar do cartão USB");main.setOnClickListener(v->{if(requireEvent())chooseCardFolder();});second.setVisibility(View.VISIBLE);}
 }
 static final int R_TITLE=0x7f0b0001,R_BODY=0x7f0b0002,R_MAIN=0x7f0b0003,R_SECOND=0x7f0b0004;
 /** Estado vazio: ícone, título, explicação e até duas ações (a primeira em destaque). */
 LinearLayout emptyState(String iconName,int tint,String title,String body,String action,View.OnClickListener l,String secondAction,View.OnClickListener l2){
  LinearLayout box=vertical();box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(20),dp(28),dp(20),dp(24));box.setBackground(border(CARD,20));
  ImageView art=new ImageView(MainActivity.this);art.setImageDrawable(LumoTheme.icon(MainActivity.this,iconName,tint,dp(32)));art.setScaleType(ImageView.ScaleType.CENTER);art.setBackground(LumoTheme.fill(CARD2,dp(32)));art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);box.addView(art,new LinearLayout.LayoutParams(dp(64),dp(64)));gap(box,14);
  TextView t=text(title,18,TEXT,true);t.setGravity(Gravity.CENTER);box.addView(t);gap(box,6);
  TextView b=text(body,14,MUTED,false);b.setGravity(Gravity.CENTER);b.setLineSpacing(dp(2),1);box.addView(b);gap(box,18);
  Button main=compactButton(action,v->{haptic(v);l.onClick(v);});main.setTextSize(15);primary(main);box.addView(main,new LinearLayout.LayoutParams(-1,-2));
  Button second=compactButton(secondAction==null?"":secondAction,v->{if(l2!=null)l2.onClick(v);});second.setTextSize(15);second.setTextColor(CYAN);second.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2222dff3),LumoTheme.fill(Color.TRANSPARENT,dp(12)),null));second.setVisibility(secondAction==null?View.GONE:View.VISIBLE);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(4);box.addView(second,sp);
  box.setTag(R_TITLE,t);box.setTag(R_BODY,b);box.setTag(R_MAIN,main);box.setTag(R_SECOND,second);return box;
 }
 void refreshFiles(boolean force){if(galleryList==null||galleryLoading||destroyed)return;fetchGallery(false,Math.max(80,galleryLoaded.size()),force);}
 void loadMoreGallery(){if(selected!=1||galleryLoading||destroyed||galleryList==null)return;if(galleryLoaded.isEmpty())return;if(galleryLoaded.size()>=galleryKnownTotal)return;fetchGallery(true,80,false);}
 void fetchGallery(boolean append,int limit,boolean force){galleryLoading=true;updateGalleryLoading();final int gen=galleryGeneration;final String album=galleryAlbum;final String event=database.activeEvent();final long before=append&&!galleryLoaded.isEmpty()?galleryLoaded.get(galleryLoaded.size()-1).rowKey:0;galleryWorker.execute(()->{try{ArrayList<PhotoItem> rows=queryGallery(album,limit,before,event);int total;try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM jobs j WHERE j.original IS NOT NULL AND j.event_id="+android.database.DatabaseUtils.sqlEscapeString(event)+filterSql()+(album.isEmpty()?"":" AND j.album=?"),album.isEmpty()?null:new String[]{album})){total=c.moveToFirst()?c.getInt(0):0;}final int all=total;handler.post(()->{if(destroyed||gen!=galleryGeneration)return;galleryLoading=false;galleryKnownTotal=all;if(append){HashSet<String> existing=new HashSet<>();for(PhotoItem x:galleryLoaded)existing.add(x.id);for(PhotoItem x:rows)if(existing.add(x.id))galleryLoaded.add(x);filesSignature=galleryFileSignature(galleryLoaded);if(rows.isEmpty())galleryKnownTotal=galleryLoaded.size();galleryAdapter.notifyDataSetChanged();}else{String sig=galleryFileSignature(rows);if(force||!sig.equals(filesSignature)){galleryLoaded.clear();galleryLoaded.addAll(rows);filesSignature=sig;galleryAdapter.notifyDataSetChanged();}}updateGallerySelectionUI();updateGalleryLoading();galleryList.post(()->{if(!galleryLoaded.isEmpty()&&galleryLoaded.size()<galleryKnownTotal&&galleryList.getChildCount()>0&&galleryList.getLastVisiblePosition()>=galleryAdapter.getCount()-2)loadMoreGallery();});});}catch(Exception e){handler.post(()->{if(destroyed||gen!=galleryGeneration)return;galleryLoading=false;gallerySpinner.setVisibility(View.GONE);galleryLoadingLabel.setText("Falha ao carregar · toque para tentar novamente");});}});}
 View galleryTile(PhotoItem item,int cell){
  LinearLayout wrap=vertical();
  FrameLayout frame=new FrameLayout(MainActivity.this);frame.setBackground(editSelection.contains(item.id)?selectedBorder(CARD,12):border(CARD,12));frame.setClipToOutline(true);wrap.addView(frame,new LinearLayout.LayoutParams(-1,cell));ImageView img=new ImageView(MainActivity.this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(darkMode?0xff20242b:0xffeef1f5,10));frame.addView(img,new FrameLayout.LayoutParams(-1,-1));loadThumb(img,item.uri,Math.max(180,cell));
  View state=statusBadge(item);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP|Gravity.RIGHT);sp.setMargins(0,dp(6),dp(6),0);frame.addView(state,sp);
  TextView score=chip(item.score>0?item.score+"/10":"—",Color.WHITE,item.score>0?scoreColor(item.score):0xff667080);FrameLayout.LayoutParams qp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);qp.setMargins(dp(6),0,0,dp(6));frame.addView(score,qp);if(item.burstCount>1){TextView burst=chip("Rajada "+item.burstCount,Color.WHITE,0xcc1b2028);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);bp.setMargins(0,0,dp(6),dp(6));frame.addView(burst,bp);}
  if(item.favorite){ImageView star=new ImageView(MainActivity.this);star.setImageDrawable(LumoTheme.icon(MainActivity.this,"star",WARN,dp(16)));star.setScaleType(ImageView.ScaleType.CENTER);star.setBackground(LumoTheme.fill(0x99000000,dp(12)));star.setContentDescription("Favorita");FrameLayout.LayoutParams fp2=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP|Gravity.LEFT);fp2.setMargins(dp(6),dp(6),0,0);frame.addView(star,fp2);}
  if(editSelection.contains(item.id)){ImageView check=new ImageView(MainActivity.this);check.setImageDrawable(LumoTheme.icon(MainActivity.this,"check",Color.WHITE,dp(18)));check.setScaleType(ImageView.ScaleType.CENTER);check.setBackground(LumoTheme.fill(LumoTheme.BLUE_SOLID,dp(13)));check.setContentDescription("Selecionada");FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.TOP|Gravity.LEFT);cp.setMargins(dp(6),dp(6),0,0);frame.addView(check,cp);}
  TextView name=text(("rawOnly".equals(item.state)?"RAW sem prévia · ":"")+item.name.replaceFirst("(?i)\\.jpg$",""),11,LumoTheme.SECONDARY,false);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(dp(3),dp(4),dp(3),0);wrap.addView(name,new LinearLayout.LayoutParams(-1,dp(27)));
  frame.setOnLongClickListener(v->{selectionMode=true;editSelection.add(item.id);filesSignature="";refreshFiles(true);return true;});frame.setOnClickListener(v->{if(selectionMode){if(editSelection.contains(item.id))editSelection.remove(item.id);else editSelection.add(item.id);if(editSelection.isEmpty())selectionMode=false;filesSignature="";refreshFiles(true);}else if(item.uri!=null&&!item.uri.isEmpty()){ArrayList<String> ids=new ArrayList<>();for(PhotoItem x:galleryLoaded)ids.add(x.id);openViewer(ids,item.id);}});return wrap;
 }
 /** Selo de estado da miniatura: círculo colorido com ícone (aprovada / revisão) ou só a cor (processando). */
 View statusBadge(PhotoItem x){ImageView v=new ImageView(MainActivity.this);v.setScaleType(ImageView.ScaleType.CENTER);v.setBackground(LumoTheme.fill(statusColor(x),dp(11)));boolean rev="review".equals(x.state),done="done".equals(x.state);if(rev||done)v.setImageDrawable(LumoTheme.icon(MainActivity.this,rev?"warning":"check",LumoTheme.ON_ACCENT,dp(14)));v.setContentDescription(rev?"Em revisão":done?"Aprovada":"Processando");return v;}
 int statusColor(PhotoItem x){if("review".equals(x.state))return WARN;if("done".equals(x.state))return SUCCESS;return GREEN;}
 int scoreColor(int score){if(score>=8)return SUCCESS;if(score>=5)return WARN;return DANGER;}
 void updateGallerySelectionUI(){if(gallerySelectionLabel==null)return;if(editSelection.isEmpty()){gallerySelectionLabel.setText("");gallerySelectionLabel.setVisibility(View.GONE);galleryActionBar.setVisibility(View.GONE);if(clearSelection!=null)clearSelection.setVisibility(View.GONE);}else{gallerySelectionLabel.setText(editSelection.size()==1?"1 foto selecionada":editSelection.size()+" fotos selecionadas");gallerySelectionLabel.setVisibility(View.VISIBLE);galleryActionBar.setVisibility(View.VISIBLE);if(clearSelection!=null)clearSelection.setVisibility(View.VISIBLE);}}
 void shareOriginals(){if(editSelection.isEmpty()){message("Segure as fotos para selecionar os originais a compartilhar.");return;}ArrayList<Uri> uris=new ArrayList<>();for(String id:editSelection)try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COALESCE(raw_original,original) FROM jobs WHERE id=?",new String[]{id})){if(c.moveToFirst()&&!c.isNull(0))uris.add(Uri.parse(c.getString(0)));}if(uris.isEmpty())return;Intent send=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);ClipData clips=ClipData.newRawUri("Originais Lumo",uris.get(0));for(int i=1;i<uris.size();i++)clips.addItem(new ClipData.Item(uris.get(i)));send.setClipData(clips);try{startActivity(Intent.createChooser(send,"Enviar originais / RAW"));}catch(Exception e){message("Não há aplicativo disponível para compartilhar esses arquivos.");}}
 boolean deletionAvailable(){if(deletingPhotos){message("Exclusão em andamento.");return false;}if(CaptureService.active!=null||FottoSync.running()||getSharedPreferences("hisho",0).getBoolean("fottoAuto",false)){message("Pare a captura/edição e desative o envio automático ao Fotto antes de excluir fotos.");return false;}return true;}
 void showDeleteFilters(){if(!deletionAvailable())return;LinearLayout box=vertical();box.setPadding(dp(16),dp(8),dp(16),dp(8));box.addView(text("Status da edição",13,TEXT,true));Spinner statusFilter=new Spinner(this);statusFilter.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Todas","Editadas","Não editadas"}));box.addView(statusFilter);box.addView(text("Data considerada",13,TEXT,true));Spinner dateType=new Spinner(this);dateType.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Importação / download","Captura da foto","Última edição"}));box.addView(dateType);
  final long[] dates={0,0};Button from=compactButton("De: qualquer data",null),to=compactButton("Até: qualquer data",null);View.OnClickListener pick=v->{int slot=v==from?0:1;Calendar now=Calendar.getInstance();new DatePickerDialog(this,(view,y,m,d)->{Calendar day=Calendar.getInstance();day.clear();day.set(y,m,d);if(slot==1)day.add(Calendar.DAY_OF_MONTH,1);dates[slot]=day.getTimeInMillis();((Button)v).setText((slot==0?"De: ":"Até: ")+String.format(Locale.getDefault(),"%02d/%02d/%04d",d,m+1,y));},now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH)).show();};from.setOnClickListener(pick);to.setOnClickListener(pick);box.addView(from);box.addView(to);
  new AlertDialog.Builder(this).setTitle("Excluir por filtros").setView(box).setPositiveButton("Ver quantidade",(dialog,n)->{if(dates[0]>0&&dates[1]>0&&dates[0]>=dates[1]){message("O início deve ser anterior ou igual ao fim.");return;}String[] columns={"downloaded_at","captured_at","edited_at"};String column=columns[dateType.getSelectedItemPosition()];String where="original IS NOT NULL"+albumWhere();ArrayList<String> args=new ArrayList<>();if(!galleryAlbum.isEmpty())args.add(galleryAlbum);int mode=statusFilter.getSelectedItemPosition();if(mode==1)where+=" AND edited_at>0";if(mode==2)where+=" AND edited_at=0";if(dateType.getSelectedItemPosition()==2)where+=" AND edited_at>0";if(dates[0]>0){where+=" AND "+column+">=?";args.add(String.valueOf(dates[0]));}if(dates[1]>0){where+=" AND "+column+"<?";args.add(String.valueOf(dates[1]));}ArrayList<String> ids=new ArrayList<>();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT id FROM jobs WHERE "+where,args.toArray(new String[0]))){while(c.moveToNext())ids.add(c.getString(0));}confirmPhotoDeletion(ids,"filtros: "+statusFilter.getSelectedItem()+" · "+dateType.getSelectedItem()+" · "+from.getText()+" · "+to.getText());}).setNegativeButton("Cancelar",null).show();
 }
 void confirmPhotoDeletion(ArrayList<String> ids,String source){if(!deletionAvailable())return;if(ids.isEmpty()){message("Nenhuma foto corresponde à "+source+".");return;}new AlertDialog.Builder(this).setTitle("Excluir "+ids.size()+" foto(s)?").setMessage(source+"\n\nApaga os originais e as cópias editadas salvas pelo Lumo no celular ou na pasta de exportação, além do histórico local. Não apaga as fotos de origem do cartão/câmera nem as já enviadas ao Fotto. Esta ação não pode ser desfeita.").setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,n)->{if(!deletionAvailable())return;deletingPhotos=true;message("Excluindo "+ids.size()+" foto(s)…");images.execute(()->{int ok=0,fail=0;for(String id:ids){try{database.deletePhoto(id);ok++;}catch(Exception e){fail++;}}final int done=ok,errors=fail;handler.post(()->{deletingPhotos=false;editSelection.clear();selectionMode=false;galleryOffset=0;filesSignature="";gallerySignature="";reviewSignature="";requestedUri="";shownUri="";thumbCache.evictAll();refreshFiles(true);refreshGallery();refreshReview(true);message(done+" foto(s) excluída(s)"+(errors>0?" · "+errors+" falha(s); confira permissões da pasta":""));});});}).show();}
 void editSelected(){if(editSelection.isEmpty()){message("Selecione uma ou mais fotos.");return;}String id=editSelection.iterator().next();openEditor(id,new ArrayList<>(editSelection),false);}
 void copyGalleryAdjustments(){if(editSelection.isEmpty()){message("Selecione uma foto para copiar os ajustes.");return;}String id=editSelection.iterator().next();String[] j=database.editorJob(id);if(j==null){message("Foto não encontrada.");return;}try{JSONObject settings=new JSONObject(j[2]);JSONObject manual=settings.optJSONObject("manual");if(manual==null)manual=new JSONObject();getSharedPreferences("hisho",0).edit().putString("manualEditClipboard",manual.toString()).apply();message("Refinamento da foto copiado. No editor você pode escolher parâmetros específicos para a próxima cópia.");}catch(Exception e){message("Não foi possível copiar os ajustes.");}}
 void pasteGalleryAdjustments(){if(editSelection.isEmpty()){message("Selecione as fotos que receberão o refinamento.");return;}if(getSharedPreferences("hisho",0).getString("manualEditClipboard","").isEmpty()){message("Copie um refinamento primeiro.");return;}openEditor(editSelection.iterator().next(),new ArrayList<>(editSelection),true);}
 void approveSelected(){if(editSelection.isEmpty()){message("Selecione fotos em revisão.");return;}ArrayList<String[]> targets=new ArrayList<>();for(String id:new ArrayList<>(editSelection)){try(Cursor c=database.getReadableDatabase().rawQuery("SELECT name,edited,state FROM jobs WHERE id=?",new String[]{id})){if(c.moveToFirst()&&"review".equals(c.getString(2)))targets.add(new String[]{id,c.getString(0),c.getString(1)});}}if(targets.isEmpty()){message("Nenhuma foto selecionada está em Revisão.");return;}editSelection.clear();selectionMode=false;for(String[] t:targets)approveReview(t[0],t[1],t[2]);}
 /** Favorita todas as selecionadas (ou desfavorita, se todas já forem favoritas). */
 void favoriteSelected(){if(editSelection.isEmpty()){message("Selecione fotos para favoritar.");return;}boolean any=false;for(String id:editSelection)if(!database.isFavorite(id)){any=true;break;}for(String id:editSelection)database.setFavorite(id,any);filesSignature="";refreshFiles(true);message(any?editSelection.size()+" foto(s) favoritada(s).":"Favoritas removidas.");}
 void sendSelected(){if(editSelection.isEmpty()){message("Selecione fotos aprovadas para enviar.");return;}if(!checkFottoReady())return;FottoSync.sendSelected(MainActivity.this,new ArrayList<>(editSelection));message("Fotos selecionadas adicionadas à entrega direta.");}

 void refreshReview(boolean force){
  if(reviewFiles==null)return;int count=database.reviewCount();if(reviewHeading!=null)reviewHeading.setText("Revisão ("+count+")");StringBuilder sig=new StringBuilder();ArrayList<PhotoItem> list=new ArrayList<>();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT id,name,edited,COALESCE(quality_note,error,'Sob revisão'),score FROM jobs WHERE "+database.eventSql()+" AND state='review' ORDER BY rowid DESC LIMIT 50",null)){while(c.moveToNext()){PhotoItem p=new PhotoItem(c.getString(0),c.getString(1),c.getString(2),"review",c.getString(3),c.getInt(4),false,database.burstSummary(c.getString(0)).length);list.add(p);sig.append(p.id).append(':').append(p.score).append(':').append(p.reason).append(';');}}
  if(!force&&sig.toString().equals(reviewSignature))return;reviewSignature=sig.toString();reviewFiles.removeAllViews();if(list.isEmpty()){gap(reviewFiles,8);reviewFiles.addView(emptyState("check",SUCCESS,"Tudo revisado","Nenhuma foto sinalizada pela curadoria. Fotos com nota baixa, tremidas ou repetidas aparecem aqui para você decidir.","Abrir galeria",v->select(1),null,null));return;}for(PhotoItem item:list)addReviewRow(item);
 }
 void addReviewRow(PhotoItem item){LinearLayout box=row();box.setPadding(dp(8),dp(8),dp(8),dp(8));box.setBackground(border(CARD,14));ImageView img=new ImageView(MainActivity.this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(CARD2,10));box.addView(img,new LinearLayout.LayoutParams(dp(96),dp(96)));loadThumb(img,item.uri,240);LinearLayout copy=vertical();copy.setPadding(dp(10),0,0,0);LinearLayout top=row();TextView name=text(("rawOnly".equals(item.state)?"RAW sem prévia · ":"")+item.name.replaceFirst("(?i)\\.jpg$",""),13,TEXT,true);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));top.addView(chip(item.score+"/10",Color.WHITE,scoreColor(item.score)));copy.addView(top);gap(copy,5);TextView reason=text(cleanReason(item.reason),11,MUTED,false);reason.setMaxLines(2);reason.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(reason);gap(copy,6);LinearLayout actions=row();Button approve=miniNav("Aprovar",v->{haptic(v);approveReview(item.id,item.name,item.uri);});approve.setTextSize(13);primary(approve);actions.addView(approve,new LinearLayout.LayoutParams(0,-2,1));Button edit=miniNav("Editar",v->openEditor(item.id,Collections.singletonList(item.id),false));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,-2,1);ep.leftMargin=dp(5);actions.addView(edit,ep);copy.addView(actions);box.addView(copy,new LinearLayout.LayoutParams(0,-2,1));box.setOnClickListener(v->{ArrayList<String> ids=new ArrayList<>();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT id FROM jobs WHERE "+database.eventSql()+" AND state='review' ORDER BY rowid DESC LIMIT 500",null)){while(c.moveToNext())ids.add(c.getString(0));}openViewer(ids,item.id);});box.setOnLongClickListener(v->{showPhotoDetails(item);return true;});reviewFiles.addView(box,new LinearLayout.LayoutParams(-1,-2));gap(reviewFiles,7);}
 String cleanReason(String r){if(r==null||r.isEmpty())return "Revisão técnica";return r.replace("Sob revisão: ","");}

 void loadThumb(ImageView image,String uri,int edge){if(uri==null||uri.isEmpty()){image.setImageResource(android.R.drawable.ic_menu_report_image);return;}Bitmap cached=thumbCache.get(uri);if(cached!=null){image.setImageBitmap(cached);return;}final long gen=stripGeneration;thumbnails.execute(()->{if(destroyed)return;Bitmap result=decodeImage(uri,edge);handler.post(()->{if(destroyed)return;if(result!=null){thumbCache.put(uri,result);image.setImageBitmap(result);}else image.setImageResource(android.R.drawable.ic_menu_report_image);});});}

 void refreshGallery(){
  if(filmStrip==null)return;ArrayList<GallerySelection.Photo> list=new ArrayList<>();ArrayList<String[]> meta=new ArrayList<>();StringBuilder signature=new StringBuilder();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT name,edited,state,score FROM jobs WHERE "+database.eventSql()+" AND state IN ('done','review') AND edited IS NOT NULL ORDER BY rowid DESC LIMIT 80",null)){while(c.moveToNext()){String uri=c.getString(1);list.add(new GallerySelection.Photo(uri,c.getString(0)));meta.add(new String[]{uri,c.getString(2),String.valueOf(c.getInt(3))});signature.append(uri).append(':').append(c.getString(2)).append(':').append(c.getInt(3)).append(';');}}
  if(signature.toString().equals(gallerySignature)){showSelection(false);return;}gallerySignature=signature.toString();Collections.reverse(list);Collections.reverse(meta);gallery.replace(list);stripGeneration++;filmStrip.removeAllViews();tiles.clear();for(int i=0;i<gallery.photos.size();i++){GallerySelection.Photo photo=gallery.photos.get(i);String[] m=meta.get(i);FrameLayout tile=new FrameLayout(MainActivity.this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(58),dp(58));lp.rightMargin=dp(6);filmStrip.addView(tile,lp);tile.setBackground(LumoTheme.fill(CARD,dp(12)));tile.setClipToOutline(true);ImageView img=new ImageView(MainActivity.this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(CARD2,12));img.setClipToOutline(true);tile.addView(img,new FrameLayout.LayoutParams(-1,-1));loadThumb(img,photo.uri,160);if(Integer.parseInt(m[2])>0){TextView sc=chip(m[2]+"",Color.WHITE,scoreColor(Integer.parseInt(m[2])));FrameLayout.LayoutParams slp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);slp.setMargins(0,0,dp(4),dp(4));tile.addView(sc,slp);}if("review".equals(m[1])){ImageView warn=new ImageView(MainActivity.this);warn.setImageDrawable(LumoTheme.icon(MainActivity.this,"warning",LumoTheme.ON_ACCENT,dp(13)));warn.setScaleType(ImageView.ScaleType.CENTER);warn.setBackground(LumoTheme.fill(WARN,dp(10)));warn.setContentDescription("Em revisão");FrameLayout.LayoutParams wlp=new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.TOP|Gravity.LEFT);wlp.setMargins(dp(4),dp(4),0,0);tile.addView(warn,wlp);}tile.setOnClickListener(v->{if(photo.uri.equals(gallery.selected))openViewerFromLive();else{gallery.select(photo.uri);showSelection(true);}});tiles.put(photo.uri,tile);}showSelection(gallery.follow);
 }
 void movePhoto(int offset){gallery.move(offset);showSelection(true);}
 void showSelection(boolean scroll){if(previousPhoto==null)return;if(liveChip!=null)liveChip.setVisibility(!gallery.follow&&!gallery.photos.isEmpty()?View.VISIBLE:View.GONE);int index=gallery.index();boolean any=index>=0;previousPhoto.setEnabled(index>0);nextPhoto.setEnabled(any&&index<gallery.photos.size()-1);livePhoto.setEnabled(!gallery.photos.isEmpty());livePhoto.setText(gallery.follow?"● Acompanhando":"Acompanhar novas");livePhoto.setTextColor(gallery.follow?GREEN:TEXT);galleryInfo.setText(gallery.photos.isEmpty()?"As fotos recentes aparecem aqui.":(any?(index+1)+" / "+gallery.photos.size():"—")+" · prévia recente; todas na Galeria");for(Map.Entry<String,FrameLayout> e:tiles.entrySet()){boolean chosen=e.getKey().equals(gallery.selected);GradientDrawable ring=LumoTheme.fill(Color.TRANSPARENT,dp(12));if(chosen)ring.setStroke(dp(2),CYAN);e.getValue().setForeground(ring);}if(!gallery.selected.isEmpty())loadPreview(gallery.selected);if(scroll){FrameLayout tile=tiles.get(gallery.selected);if(tile!=null)filmScroll.post(()->filmScroll.smoothScrollTo(Math.max(0,tile.getLeft()-(filmScroll.getWidth()-tile.getWidth())/2),0));}}
 Bitmap decodeImage(String value,int edge){try{Uri uri=Uri.parse(value);BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opts);}if(opts.outWidth<=0||opts.outHeight<=0)return null;opts.inJustDecodeBounds=false;opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>edge)opts.inSampleSize*=2;try(InputStream in=getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(in,null,opts);}}catch(Exception e){return null;}}
 void loadPreview(String value){if(value.equals(requestedUri))return;requestedUri=value;final long gen=++previewGeneration;preview.setImageDrawable(null);shownUri="";previewTitle.setText(gallery.name());previewSub.setText("Carregando…");images.execute(()->{if(destroyed||gen!=previewGeneration)return;final Bitmap ready=decodeImage(value,1500);String id=database.idForEdited(value);int score=id.isEmpty()?0:database.quality(id)[0];String st="";try(Cursor c=database.getReadableDatabase().rawQuery("SELECT state FROM jobs WHERE id=?",new String[]{id})){if(c.moveToFirst())st=c.getString(0);}final String state=st;handler.post(()->{if(destroyed||gen!=previewGeneration){if(ready!=null)ready.recycle();return;}empty.setVisibility(View.GONE);if(ready==null){previewSub.setText("Arquivo indisponível");return;}if(previewBitmap!=null&&previewBitmap!=ready)previewBitmap.recycle();previewBitmap=ready;preview.setImageBitmap(ready);shownUri=value;if(previewFooter!=null)previewFooter.setVisibility(View.VISIBLE);boolean rev="review".equals(state);previewTitle.setText(rev?"Revisão":"Aprovada");previewTitle.setTextColor(rev?WARN:SUCCESS);previewTitle.setCompoundDrawablesRelative(LumoTheme.icon(MainActivity.this,rev?"warning":"check",rev?WARN:SUCCESS,dp(16)),null,null,null);previewTitle.setCompoundDrawablePadding(dp(5));previewSub.setText(gallery.name()+" · toque para abrir");if(previewScore!=null){previewScore.setVisibility(score>0?View.VISIBLE:View.GONE);previewScore.setText(score+"\n/10");previewScore.setBackground(LumoTheme.card(0x8c000000,dp(14),dp(1)));}});});}
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
 boolean checkFottoReady(){if(FottoApi.token(MainActivity.this).isEmpty()){message("Conecte sua conta Fotto na Entrega.");return false;}if(getSharedPreferences("hisho",0).getString("fottoGalleryId","").isEmpty()){message("Escolha um evento Fotto primeiro.");return false;}return true;}
 void deliverMenu(){
  boolean connected=!FottoApi.token(MainActivity.this).isEmpty();
  String title=getSharedPreferences("hisho",0).getString("fottoGalleryTitle","");
  new LumoSheet(this,"Entrega direta",connected?(title.isEmpty()?"Conta conectada · escolha o evento":"Destino: "+title):"Conecte a conta do Fotto para enviar")
   .section("Conta e destino")
   .item("person",CYAN,connected?"Reconectar conta Fotto":"Conectar conta Fotto",connected?"Use se a sessão expirar":"Entrar com a conta do fotógrafo",()->startActivityForResult(new Intent(this,FottoLoginActivity.class),30))
   .item("event",CYAN,"Trocar evento",title.isEmpty()?"Nenhum evento escolhido":title,()->{if(!connected){message("Conecte o Fotto primeiro.");return;}if(fottoGalleries.isEmpty()){openEventChooserAfterLoad=true;loadFottoGalleries();}else showEventChooser();})
   .item("refresh",CYAN,"Atualizar lista completa","Busca todas as páginas de eventos do Fotto",()->{openEventChooserAfterLoad=true;loadFottoGalleries();})
   .item("search",CYAN,"Informar ID do evento","ID ou link do painel com /galleries/ID",this::showManualEventDialog)
   .section("Envio")
   .item("upload",CYAN,"Enviar todas as pendentes","Fotos aprovadas que ainda não foram enviadas",()->{if(checkFottoReady())FottoSync.sendExisting(this);})
   .item("open",CYAN,"Abrir Fotto no navegador","Conferir o evento ou alternativa ao envio direto",this::openFottoWeb)
   .item("folder",CYAN,"Alterar pasta de exportação","Onde o Lumo salva originais e editadas",this::chooseExportFolder)
   .section("Para os convidados")
   .item("photos",CYAN,"Galeria online",galleryMenuSubtitle(),this::galleryMenu)
   .show();
 }

 // ---------------------------------------------------------------- galeria online (online-gallery/worker)
 String galleryMenuSubtitle(){
  if(!GalleryApi.configured(this))return "Link para os convidados verem, favoritarem e baixarem";
  GalleryApi.Gallery g=GalleryApi.galleryFor(this,database.activeEvent());
  return g==null?"Crie a galeria deste evento":"Publicando em: "+g.title;
 }
 void galleryMenu(){
  boolean connected=GalleryApi.configured(this);
  String event=database.activeEvent();GalleryApi.Gallery g=connected?GalleryApi.galleryFor(this,event):null;
  LumoSheet sheet=new LumoSheet(this,"Galeria online",connected?(g==null?"Evento: "+database.eventName():g.title):"Os convidados recebem um link (ou QR code) para ver, favoritar e baixar as fotos. Complementa o Fotto.");
  if(!connected){
   sheet.item("person",CYAN,"Conectar servidor da galeria","Endereço workers.dev e chave de acesso",this::connectGalleryDialog).show();return;
  }
  if(g==null){
   sheet.item("add",CYAN,"Criar galeria deste evento",database.eventName(),this::createGalleryDialog);
  }else{
   int done=database.onlineDone(g.id),pending=database.onlinePending(g.id,event),errors=database.onlineErrors(g.id);
   sheet.status("photos",CYAN,"Publicadas",String.valueOf(done));
   if(pending>0)sheet.status("clock",CYAN,"Aguardando envio",String.valueOf(pending));
   if(errors>0)sheet.status("warning",DANGER,"Com erro",String.valueOf(errors));
   String status=GallerySync.status(this);if(!status.isEmpty())sheet.text(status);
   sheet.section("Convidados")
    .item("share",CYAN,"Compartilhar link","WhatsApp, Instagram, e-mail…"+(g.hasPin?" (lembre de mandar a senha)":""),()->shareGalleryLink(g))
    .item("file",CYAN,"Copiar link",g.url,()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Galeria",g.url));message("Link copiado.");})
    .item("open",CYAN,"Painel no navegador","QR code para imprimir, favoritas dos convidados e configurações",()->openUrl(GalleryApi.server(this)+"/admin#g="+g.id))
    .section("Envio")
    .choice("bolt",CYAN,"Publicar automaticamente","Cada foto aprovada vai para a galeria",GallerySync.auto(this),()->{boolean on=!GallerySync.auto(this);GallerySync.setAuto(this,on);message(on?"Publicação automática ligada.":"Publicação automática desligada.");})
    .item("upload",CYAN,"Enviar pendentes agora",pending==0?"Nada pendente":pending+" foto(s)",()->{GallerySync.sendNow(this);message("Enviando para a galeria online…");})
    .item("refresh",CYAN,"Trocar galeria deste evento","Desliga este evento da galeria atual (nada é apagado online)",()->{GalleryApi.forgetGallery(this,event);message("Galeria desligada deste evento.");});
  }
  sheet.section("Servidor").item("person",CYAN,"Trocar servidor ou chave",GalleryApi.server(this).replaceFirst("^https://",""),this::connectGalleryDialog).show();
 }
 void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){message("Nenhum navegador disponível.");}}
 void shareGalleryLink(GalleryApi.Gallery g){
  String text="Fotos do evento "+g.title+": "+g.url;
  startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,g.title).putExtra(Intent.EXTRA_TEXT,text),"Compartilhar galeria"));
 }
 void connectGalleryDialog(){
  EditText server=new EditText(this);server.setSingleLine(true);server.setHint("lumo-galeria.suaconta.workers.dev");server.setTextSize(14);server.setText(GalleryApi.server(this).replaceFirst("^https://",""));server.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
  EditText key=new EditText(this);key.setSingleLine(true);key.setHint("Chave de acesso (ADMIN_TOKEN)");key.setTextSize(14);key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
  LinearLayout body=vertical();body.setPadding(dp(20),dp(4),dp(20),0);
  label(body,"Endereço do servidor publicado na Cloudflare e a mesma chave usada no painel. Veja online-gallery/README.md.",12);
  body.addView(server,new LinearLayout.LayoutParams(-1,dp(58)));body.addView(key,new LinearLayout.LayoutParams(-1,dp(58)));
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Servidor da galeria").setView(body).setNegativeButton("Cancelar",null).setPositiveButton("Conectar",null).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   String url=GalleryApi.normalizeServer(server.getText().toString()),token=key.getText().toString().trim();
   if(url.isEmpty()){server.setError("Informe o endereço.");return;}if(token.isEmpty()){key.setError("Informe a chave.");return;}
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
   fottoNetwork.execute(()->{
    try{String studio=GalleryApi.check(url,token);getSharedPreferences("hisho",0).edit().putString("lgServer",url).putString("lgToken",token).apply();
     handler.post(()->{if(destroyed)return;dialog.dismiss();message("Galeria conectada · "+studio);galleryMenu();});}
    catch(Exception e){handler.post(()->{if(destroyed)return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);key.setError(e.getMessage());});}
   });
  }));dialog.show();
 }
 void createGalleryDialog(){
  String event=database.activeEvent();
  EditText title=new EditText(this);title.setSingleLine(true);title.setHint("Nome que os convidados veem");title.setTextSize(14);title.setText(database.eventName());
  EditText pin=new EditText(this);pin.setSingleLine(true);pin.setHint("Senha (opcional, 4 ou mais letras/números)");pin.setTextSize(14);
  CheckBox download=new CheckBox(this);download.setText("Convidados podem baixar em alta");download.setChecked(true);download.setTextColor(TEXT);
  LinearLayout body=vertical();body.setPadding(dp(20),dp(4),dp(20),0);
  label(body,"As fotos aprovadas deste evento serão publicadas nela. Anote a senha: ela não pode ser vista depois.",12);
  body.addView(title,new LinearLayout.LayoutParams(-1,dp(58)));body.addView(pin,new LinearLayout.LayoutParams(-1,dp(58)));body.addView(download);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nova galeria online").setView(body).setNegativeButton("Cancelar",null).setPositiveButton("Criar",null).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   String name=title.getText().toString().trim(),secret=pin.getText().toString().trim();
   if(name.isEmpty()){title.setError("Informe o nome.");return;}
   if(!secret.isEmpty()&&!secret.matches("[0-9A-Za-z]{4,32}")){pin.setError("Use de 4 a 32 letras ou números.");return;}
   boolean allow=download.isChecked();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
   fottoNetwork.execute(()->{
    try{GalleryApi.Gallery g=GalleryApi.createGallery(this,name,event,secret,allow);GalleryApi.saveGallery(this,event,g);GallerySync.sendNow(this);
     handler.post(()->{if(destroyed)return;dialog.dismiss();message("Galeria criada. Publicando as fotos aprovadas…");galleryMenu();});}
    catch(Exception e){handler.post(()->{if(destroyed)return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);title.setError(e.getMessage());});}
   });
  }));dialog.show();
 }
 static String normalizeEventText(String value){
  String text=java.text.Normalizer.normalize(value==null?"":value,java.text.Normalizer.Form.NFD);
  return text.replaceAll("\\p{M}+","").toLowerCase(java.util.Locale.ROOT).trim();
 }
 void showEventChooser(){
  if(destroyed)return;
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);
  String current=prefs.getString("fottoGalleryId","");
  LinearLayout content=vertical();content.setPadding(dp(16),dp(8),dp(16),dp(6));
  String info=fottoEventsSummary.isEmpty()?fottoGalleries.size()+" evento(s) carregado(s)":fottoEventsSummary;
  TextView count=text(info,12,MUTED,false);content.addView(count);
  if(!current.isEmpty()){
   TextView saved=text("Destino atual: "+prefs.getString("fottoGalleryTitle",current),12,SUCCESS,true);
   saved.setPadding(0,dp(7),0,dp(4));content.addView(saved);
  }
  EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Buscar por nome ou ID (eventos antigos)");
  search.setTextSize(14);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
  content.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
  ListView events=new ListView(this);events.setDividerHeight(dp(1));events.setFastScrollEnabled(true);
  content.addView(events,new LinearLayout.LayoutParams(-1,dp(320)));
  ArrayList<FottoApi.Gallery> filtered=new ArrayList<>();
  ArrayList<String> labels=new ArrayList<>();
  ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);
  events.setAdapter(adapter);
  Runnable filter=()->{
   String q=normalizeEventText(search.getText().toString());filtered.clear();labels.clear();
   for(FottoApi.Gallery g:fottoGalleries){
    if(!q.isEmpty()&&!normalizeEventText(g.title+" "+g.id).contains(q))continue;
    filtered.add(g);labels.add((g.id.equals(current)?"✓ ":"")+g.title+"\n"+g.id);
   }
   adapter.notifyDataSetChanged();
   count.setText((q.isEmpty()?"":filtered.size()+" encontrado(s) de ")+info+(filtered.isEmpty()?"\nSe não aparecer, use Informar ID.":""));
  };
  search.addTextChangedListener(new android.text.TextWatcher(){
   public void beforeTextChanged(CharSequence t,int start,int count,int after){}
   public void onTextChanged(CharSequence t,int start,int before,int count){filter.run();}
   public void afterTextChanged(android.text.Editable e){}
  });
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Selecionar evento Fotto")
   .setView(content).setNegativeButton("Fechar",null)
   .setNeutralButton("Informar ID",(d,w)->showManualEventDialog())
   .setPositiveButton("Recarregar",(d,w)->{openEventChooserAfterLoad=true;loadFottoGalleries();}).create();
  events.setOnItemClickListener((parent,view,position,id)->{
   FottoApi.Gallery selected=filtered.get(position);
   confirmEventSelection(selected,()->dialog.dismiss());
  });
  filter.run();dialog.show();
 }
 void confirmEventSelection(FottoApi.Gallery gallery,Runnable onSelected){
  android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);
  String current=prefs.getString("fottoGalleryId","");
  if(gallery.id.equals(current)){if(onSelected!=null)onSelected.run();return;}
  if(FottoSync.running()){
   message("Encerre o envio atual antes de trocar de evento para não misturar fotos.");return;
  }
  new AlertDialog.Builder(this).setTitle("Confirmar destino das fotos")
   .setMessage("Enviar as próximas fotos para este evento?\n\n"+gallery.title+"\nID: "+gallery.id+"\n\nO destino atual não será alterado até você confirmar.")
   .setNegativeButton("Voltar",null).setPositiveButton("Selecionar evento",(d,w)->{
    android.content.SharedPreferences.Editor editor=prefs.edit().putString("fottoGalleryId",gallery.id)
     .putString("fottoGalleryTitle",gallery.title).putString("fottoLastStatus","Evento selecionado: "+gallery.title);
    if(prefs.getBoolean("fottoAuto",false))editor.putLong("fottoStartRowid",database.maxRowId());
    editor.apply();
    boolean found=false;for(FottoApi.Gallery g:fottoGalleries)if(g.id.equals(gallery.id)){found=true;break;}
    if(!found)fottoGalleries.add(0,gallery);
    updateFottoStatus();if(onSelected!=null)onSelected.run();FottoSync.kick(this);
   }).show();
 }
 static String parseFottoEventId(String input){
  String value=input==null?"":input.trim();
  if(value.startsWith("https://")||value.startsWith("http://")){
   try{
    java.net.URI u=new java.net.URI(value);String host=u.getHost();
    if(host==null||!(host.equalsIgnoreCase("fotto.com.br")||host.toLowerCase(java.util.Locale.ROOT).endsWith(".fotto.com.br")))return "";
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("/(?:galleries|gallery)/([A-Za-z0-9_-]{3,120})(?:/|$)").matcher(u.getPath());
    if(m.find())return m.group(1);
    String q=u.getRawQuery();if(q!=null){m=java.util.regex.Pattern.compile("(?:^|&)(?:galleryId|gallery_id|eventId|event_id)=([A-Za-z0-9_-]{3,120})(?:&|$)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(q);if(m.find())return m.group(1);}
   }catch(Exception ignored){}return "";
  }
  return value.matches("[A-Za-z0-9_-]{3,120}")?value:"";
 }
 void showManualEventDialog(){
  if(FottoApi.token(this).isEmpty()){message("Conecte a conta Fotto antes de informar o evento.");return;}
  EditText entry=new EditText(this);entry.setSingleLine(true);entry.setHint("ID do evento ou link do painel Fotto");entry.setTextSize(14);
  LinearLayout body=vertical();body.setPadding(dp(20),dp(4),dp(20),0);
  label(body,"Para eventos que não aparecem na listagem, use o ID do painel do fotógrafo. Links públicos sem ID não funcionam.",12);
  body.addView(entry,new LinearLayout.LayoutParams(-1,dp(58)));
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Localizar evento pelo ID")
   .setView(body).setNegativeButton("Cancelar",null).setPositiveButton("Verificar evento",null).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   String eventId=parseFottoEventId(entry.getText().toString());
   if(eventId.isEmpty()){entry.setError("Informe o ID, não apenas o link público do evento.");return;}
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
   fottoNetwork.execute(()->{
    try{
     FottoApi.Gallery found=FottoApi.galleryById(this,eventId);
     handler.post(()->{if(destroyed)return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);confirmEventSelection(found,()->dialog.dismiss());});
    }catch(Exception e){handler.post(()->{if(destroyed)return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);new AlertDialog.Builder(this).setTitle("Evento não confirmado").setMessage(e.getMessage()).setPositiveButton("OK",null).show();});}
   });
  }));dialog.show();
 }
 void updateFottoStatus(){if(fottoStatus==null)return;android.content.SharedPreferences prefs=getSharedPreferences("hisho",0);String token=FottoApi.token(MainActivity.this),galleryId=prefs.getString("fottoGalleryId","");String galleryTitle=prefs.getString("fottoGalleryTitle","");boolean active=prefs.getBoolean("fottoAuto",false);fottoStatus.setText(token.isEmpty()?"Fotto • Não conectado":FottoSync.running()?"Fotto • Enviando…":"Fotto • Conectado");fottoStatus.setTextColor(token.isEmpty()?MUTED:(FottoSync.running()?WARN:SUCCESS));fottoEventLabel.setText("Evento: "+(galleryTitle.isEmpty()?"—":galleryTitle)+(galleryId.isEmpty()?"":" · "+galleryId));int sent=galleryId.isEmpty()?prefs.getInt("fottoUploadedCount",0):database.fottoDone(galleryId);int pending=galleryId.isEmpty()?0:database.fottoPending(galleryId);int processing=galleryId.isEmpty()?0:database.fottoProcessing(galleryId);String last=prefs.getString("fottoLastStatus",active?"Envio automático ativo":"Envio automático pausado");fottoUploadStatus.setText(sent+" confirmadas · "+pending+" na fila"+(processing>0?" · "+processing+" processando":"")+(FottoSync.running()?" · sincronizando…":"")+"\n"+last);String dbError=galleryId.isEmpty()?"":database.fottoLastError(galleryId);boolean failure=(last!=null&&(last.startsWith("Falha")||last.contains("erro")||last.contains("Erro")||last.contains("Não confirmada")))||!dbError.isEmpty();fottoProblems.setVisibility(failure?View.VISIBLE:View.GONE);if(failure)fottoProblems.setText("Problemas\n"+(!dbError.isEmpty()?dbError:last));if(fottoAuto!=null&&fottoAuto.isChecked()!=active){fottoAuto.setOnCheckedChangeListener(null);fottoAuto.setChecked(active);bindFottoToggle();}updateFottoActions(token.isEmpty(),galleryId.isEmpty(),active);}
 /** O botão principal da Entrega mostra sempre o próximo passo; "Encerrar envio" só aparece quando há envio a encerrar. */
 void updateFottoActions(boolean noAccount,boolean noEvent,boolean auto){if(fottoSend==null)return;String label=noAccount?"Conectar conta Fotto":noEvent?"Escolher evento do Fotto":"Enviar pendentes agora";if(!label.contentEquals(fottoSend.getText()))fottoSend.setText(label);if(fottoStop!=null)fottoStop.setVisibility(FottoSync.running()||auto?View.VISIBLE:View.GONE);}
 void fottoPrimaryAction(){if(FottoApi.token(MainActivity.this).isEmpty()){startActivityForResult(new Intent(MainActivity.this,FottoLoginActivity.class),30);return;}if(getSharedPreferences("hisho",0).getString("fottoGalleryId","").isEmpty()){chooseFottoEvent();return;}FottoSync.sendExisting(MainActivity.this);getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Procurando Editadas pendentes…").apply();updateFottoStatus();}
 void chooseFottoEvent(){if(fottoGalleries.isEmpty()){openEventChooserAfterLoad=true;loadFottoGalleries();}else showEventChooser();}
 void bindFottoToggle(){if(fottoAuto==null)return;fottoAuto.setOnCheckedChangeListener((b,checked)->{if(checked){if(deletingPhotos){b.setChecked(false);message("Aguarde a exclusão terminar.");return;}if(FottoApi.token(MainActivity.this).isEmpty()){message("Conecte o Fotto antes de ativar o envio automático.");getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",false).apply();b.setChecked(false);return;}String galleryId=getSharedPreferences("hisho",0).getString("fottoGalleryId","");if(galleryId.isEmpty()){message("Escolha um evento antes de ativar o envio automático.");getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",false).apply();b.setChecked(false);return;}getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",true).putLong("fottoStartRowid",database.maxRowId()).putString("fottoLastStatus","Envio direto automático ativo").apply();FottoSync.kick(MainActivity.this);}else getSharedPreferences("hisho",0).edit().putBoolean("fottoAuto",false).putString("fottoLastStatus","Envio direto automático pausado").apply();updateFottoStatus();});}
 void loadFottoGalleries(){
  if(FottoApi.token(this).isEmpty()){updateFottoStatus();return;}
  if(fottoGalleriesLoading)return;
  fottoGalleriesLoading=true;
  if(fottoStatus!=null)fottoStatus.setText("Fotto • Buscando todos os eventos…");
  fottoNetwork.execute(()->{
   try{
    FottoApi.GalleryListing result=FottoApi.galleriesDetailed(this);
    handler.post(()->{
     fottoGalleriesLoading=false;if(destroyed)return;
     fottoGalleries.clear();fottoGalleries.addAll(result.items);
     String saved=getSharedPreferences("hisho",0).getString("fottoGalleryId","");
     // Never silently replace a previously selected older event with the newest.
     boolean present=saved.isEmpty();for(FottoApi.Gallery g:fottoGalleries)if(g.id.equals(saved)){present=true;break;}
     if(!present){
      String savedTitle=getSharedPreferences("hisho",0).getString("fottoGalleryTitle","Evento salvo");
      fottoGalleries.add(0,new FottoApi.Gallery(saved,savedTitle));
     }
     fottoEventsSummary=result.items.size()+" evento(s) recebido(s) da conta"+(result.complete?" · fim da lista":" · listagem não confirmada")
      +(result.warning.isEmpty()?"":"\n"+result.warning);
     if(result.items.isEmpty()&&saved.isEmpty())getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Nenhum evento encontrado. Tente informar o ID.").apply();
     updateFottoStatus();
     if(openEventChooserAfterLoad){openEventChooserAfterLoad=false;showEventChooser();}
    });
   }catch(Exception e){handler.post(()->{
    fottoGalleriesLoading=false;if(destroyed)return;
    openEventChooserAfterLoad=false;
    getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Falha ao carregar eventos · "+e.getMessage()).apply();
    updateFottoStatus();message("Falha na consulta. Use Informar ID ou tente atualizar novamente.");
   });}
  });
 }
 void approveReview(String id,String name,String reviewUri){message("Aprovando foto…");images.execute(()->{File tmp=new File(getCacheDir(),"approve_"+id+".jpg");boolean cleared=false;try(InputStream in=getContentResolver().openInputStream(Uri.parse(reviewUri));OutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("Foto de revisão indisponível.");Jobs.copy(in,out);database.clearEdited(id);cleared=true;Uri ready=database.save(tmp,"Editadas",name.replaceFirst("(?i)\\.jpg$","_Editada.jpg"),id,"edited");database.set(id,"done","error",null);database.history(id,System.currentTimeMillis(),"Aprovada","Aprovação manual na Revisão");try{getContentResolver().delete(Uri.parse(reviewUri),null,null);}catch(Exception ignored){}FottoSync.kick(MainActivity.this);handler.post(()->{gallerySignature="";filesSignature="";reviewSignature="";refreshGallery();refreshFiles(true);refreshReview(true);message("Foto aprovada e liberada para entrega.");});}catch(Exception e){if(cleared)database.set(id,"review","edited",reviewUri);handler.post(()->message("Não foi possível aprovar: "+e.getMessage()));}finally{tmp.delete();}});}
 void openImage(String uri){try{Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);}catch(Exception e){message("Não foi possível abrir esta foto.");}}
 void diagnostic(){TextView t=text(report==null?"":report.getText().toString(),12,MUTED,false);t.setTextIsSelectable(true);t.setPadding(dp(16),dp(16),dp(16),dp(16));ScrollView scroll=new ScrollView(MainActivity.this);scroll.addView(t);new AlertDialog.Builder(MainActivity.this).setTitle("Diagnóstico da sessão").setView(scroll).setPositiveButton("Copiar",(d,n)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("LUMO",t.getText()));message("Diagnóstico copiado.");}).setNegativeButton("Fechar",null).show();}

 class LumoMark extends View{Paint p=new Paint(3);LumoMark(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),r=Math.min(w,h)*.34f;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.min(w,h)*.24f);p.setStrokeCap(Paint.Cap.ROUND);p.setShader(new SweepGradient(w/2f,h/2f,new int[]{0xff22dff3,0xff0072ff,0xff2336d7,0xff22dff3},null));c.drawCircle(w/2f,h/2f,r,p);p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(CARD);c.drawCircle(w*.60f,h*.43f,Math.min(w,h)*.12f,p);}}
 class CameraArt extends View{Paint paint=new Paint(3);CameraArt(Context c){super(c);}protected void onDraw(Canvas canvas){super.onDraw(canvas);float sx=getWidth()/110f,sy=getHeight()/100f;canvas.save();canvas.scale(sx,sy);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff172231);canvas.drawCircle(55,50,45,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(CYAN);canvas.drawRoundRect(24,34,86,73,8,8,paint);canvas.drawCircle(55,54,13,paint);canvas.drawCircle(55,54,7,paint);canvas.drawLine(36,33,41,25,paint);canvas.drawLine(41,25,64,25,paint);canvas.drawLine(64,25,70,33,paint);canvas.restore();}}

 boolean eventChangeAllowed(){if(CaptureService.active!=null||importingEvent||deletingPhotos||FottoSync.running()){message("Encerre a captura e aguarde a importação/edição/envio em andamento antes de trocar de evento.");return false;}return true;}
 boolean requireEvent(){if(!database.activeEvent().isEmpty())return true;message("Crie ou selecione um evento antes de receber fotos.");chooseLocalEvent();return false;}
 void chooseLocalEvent(){if(!eventChangeAllowed())return;ArrayList<String[]> events=database.events();HashMap<String,Integer> counts=eventPhotoCounts();String active=database.activeEvent();
  LumoSheet sheet=new LumoSheet(this,"Eventos","Cada evento guarda seus originais, editadas, revisão e destino no Fotto.");
  sheet.item("add",CYAN,"Criar evento","Novo evento, já selecionado",this::createLocalEvent);
  sheet.section("Seus eventos");
  for(String[] e:events){final String id=e[0];Integer n=counts.get(id);int total=n==null?0:n;String photos=total==0?"Sem fotos":total==1?"1 foto":total+" fotos";boolean legacy=id.isEmpty();
   sheet.choice(legacy?"history":"event",CYAN,e[1],legacy?"Antes dos eventos · "+photos:photos,id.equals(active),()->{if(!id.equals(database.activeEvent()))selectLocalEvent(id);});}
  sheet.show();}
 /** Quantidade de fotos por evento, para a lista de eventos. */
 HashMap<String,Integer> eventPhotoCounts(){HashMap<String,Integer> out=new HashMap<>();try(Cursor c=database.getReadableDatabase().rawQuery("SELECT COALESCE(event_id,''),COUNT(*) FROM jobs WHERE original IS NOT NULL GROUP BY COALESCE(event_id,'')",null)){while(c.moveToNext())out.put(c.getString(0),c.getInt(1));}catch(Exception ignored){}return out;}
 void createLocalEvent(){EditText input=new EditText(this);input.setHint("Nome do evento");input.setSingleLine(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Criar evento").setView(input).setPositiveButton("Criar e selecionar",null).setNegativeButton("Cancelar",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("Informe o nome do evento");return;}if(!eventChangeAllowed())return;String id=database.createEvent(name);dialog.dismiss();selectLocalEvent(id);}));dialog.show();}
 void selectLocalEvent(String id){if(!eventChangeAllowed())return;android.content.SharedPreferences p=getSharedPreferences("hisho",0);String old=database.activeEvent();FottoSync.stop(this);p.edit().putString("eventFottoId:"+old,p.getString("fottoGalleryId","")).putString("eventFottoTitle:"+old,p.getString("fottoGalleryTitle","")).putString("localEventId",id).putString("fottoGalleryId",p.getString("eventFottoId:"+id,"")).putString("fottoGalleryTitle",p.getString("eventFottoTitle:"+id,"")).putBoolean("fottoAuto",false).putString("lastImage","").apply();galleryAlbum="";editSelection.clear();selectionMode=false;galleryOffset=0;resetGalleryAlbum();if(albumButton!=null)albumButton.setText("Álbum: todos");if(eventTitle!=null)eventTitle.setText(database.eventName());gallerySignature="__event_changed__";reviewSignature="__event_changed__";previewGeneration++;requestedUri="";shownUri="";gallery.selected="";gallery.follow=true;preview.setImageDrawable(null);empty.setVisibility(View.VISIBLE);previewTitle.setText("");previewSub.setText("");if(previewFooter!=null)previewFooter.setVisibility(View.GONE);publishLiveSettings();refreshFiles(true);refreshGallery();refreshReview(true);updateFottoStatus();message("Evento selecionado: "+database.eventName()+". Confira o destino na aba Entrega antes de ativar o envio.");}
 void chooseTransport(){if(!requireEvent())return;new LumoSheet(this,"Conectar câmera","Ligue a câmera e use um cabo de dados USB/OTG. Wi-Fi da câmera desligado.").item("usb",CYAN,"Cabo USB","Recomendado · mais estável e rápido",this::choose).item("wifi",CYAN,"Wi-Fi","Experimental · conecte o aparelho à rede da câmera",this::wifiDialog).show();}
 void wifiDialog(){android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);android.net.Network found=null;String gateway="192.168.0.1";for(android.net.Network n:cm.getAllNetworks()){android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)){found=n;android.net.LinkProperties props=cm.getLinkProperties(n);if(props!=null)for(android.net.RouteInfo route:props.getRoutes())if(route.isDefaultRoute()&&route.getGateway() instanceof java.net.Inet4Address)gateway=route.getGateway().getHostAddress();break;}}final android.net.Network wifi=found;LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));label(box,"Na câmera: Wi-Fi → Controle remoto/EOS Utility. Conecte o celular à rede da câmera e autorize o LUMO.",13);EditText ip=new EditText(MainActivity.this);ip.setText(getSharedPreferences("hisho",0).getString("wifiHost",gateway));ip.setTextColor(TEXT);ip.setHint("IP da câmera");ip.setInputType(android.text.InputType.TYPE_CLASS_PHONE);box.addView(ip);AlertDialog dialog=new AlertDialog.Builder(MainActivity.this).setTitle("Conectar por Wi-Fi").setView(box).setPositiveButton("Conectar",null).setNeutralButton("Abrir Wi-Fi",(d,n)->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))).setNegativeButton("Cancelar",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String host=ip.getText().toString().trim();if(!validIp(host)){ip.setError("Informe um IPv4 válido.");return;}if(wifi==null){message("Conecte ao Wi-Fi da câmera primeiro.");return;}getSharedPreferences("hisho",0).edit().putString("wifiHost",host).apply();dialog.dismiss();startCamera(null,host,wifi);}));dialog.show();}
 static boolean validIp(String host){String[] parts=host.split("\\.",-1);if(parts.length!=4)return false;for(String part:parts){if(!part.matches("[0-9]{1,3}"))return false;int v=Integer.parseInt(part);if(v>255)return false;}return true;}
 void choose(){UsbManager m=(UsbManager)getSystemService(USB_SERVICE);ArrayList<UsbDevice> ds=new ArrayList<>();for(UsbDevice d:m.getDeviceList().values())if(d.getVendorId()==0x04a9)ds.add(d);if(ds.isEmpty()){message("Nenhuma Canon encontrada. Conecte um cabo USB/OTG de dados.");return;}if(ds.size()==1){permission(ds.get(0));return;}LumoSheet sheet=new LumoSheet(this,"Escolha a câmera",ds.size()+" câmeras Canon conectadas");for(UsbDevice d:ds)sheet.item("camera",CYAN,d.getProductName()==null?"Câmera Canon":d.getProductName(),d.getManufacturerName(),()->permission(d));sheet.show();}
 void permission(UsbDevice d){UsbManager m=(UsbManager)getSystemService(USB_SERVICE);if(m.hasPermission(d)){start(d);return;}Intent i=new Intent(USB).setPackage(getPackageName());PendingIntent pi=PendingIntent.getBroadcast(MainActivity.this,1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);m.requestPermission(d,pi);}
 void start(UsbDevice d){startCamera(d,null,null);}
 void startCamera(UsbDevice d,String wifiHost,android.net.Network wifiNetwork){if(!requireEvent())return;if(deletingPhotos){message("Aguarde a exclusão terminar.");return;}if(CaptureService.active!=null){message("Serviço já ativo.");return;}try{JSONObject cfg=buildCurrentSettings();getSharedPreferences("hisho",0).edit().putString("liveSettings",cfg.toString()).apply();Intent i=new Intent(MainActivity.this,CaptureService.class).putExtra("settings",cfg.toString());if(d!=null)i.putExtra("device",d);if(wifiHost!=null)i.putExtra("wifiHost",wifiHost).putExtra("wifiNetwork",wifiNetwork);startForegroundService(i);}catch(Exception e){message(e.getMessage());}}
 static final int REQUEST_CARD_FOLDER=40;
 static final class CardPhoto{final Uri uri;final String name,album;final long size,modified;CardPhoto(Uri u,String n,long s,long m,String a){uri=u;name=n;size=s;modified=m;album=a;}}
 void chooseCardFolder(){if(!requireEvent())return;
  Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  try{startActivityForResult(i,REQUEST_CARD_FOLDER);}catch(Exception e){message("O seletor de pastas não está disponível neste aparelho.");}
 }
 ArrayList<CardPhoto> scanCardFolder(Uri tree,Uri folder,int depth,Set<String> visited)throws IOException{
  if(importCancelRequested)throw new CancellationException();
  if(depth>24)throw new IOException("A estrutura de pastas do cartão excede o limite de segurança.");
  String folderId=DocumentsContract.getDocumentId(folder);if(!visited.add(folderId))return new ArrayList<>();
  Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folderId);
  String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
  ArrayList<CardPhoto> photos=new ArrayList<>();
  try(Cursor c=getContentResolver().query(children,columns,null,null,null)){
   if(c==null)throw new IOException("Não foi possível ler uma pasta do cartão.");
   int idCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),nameCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),mimeCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),sizeCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),modifiedCol=c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
   while(c.moveToNext()){
    String id=c.getString(idCol),name=nameCol>=0?c.getString(nameCol):"foto";String mime=mimeCol>=0?c.getString(mimeCol):"";
    Uri child=DocumentsContract.buildDocumentUriUsingTree(tree,id);
    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))photos.addAll(scanCardFolder(tree,child,depth+1,visited));
    else if(isSupportedCardPhoto(name,mime)){long size=sizeCol>=0&&!c.isNull(sizeCol)?c.getLong(sizeCol):-1;long modified=modifiedCol>=0&&!c.isNull(modifiedCol)?c.getLong(modifiedCol):0;photos.add(new CardPhoto(child,name,size,modified,folderId.replaceFirst("^[^:]*:","")));importFound++;}
   }
  }catch(SecurityException e){throw new IOException("Acesso ao cartão USB foi negado.",e);}
  return photos;
 }
 static boolean isSupportedCardPhoto(String name,String mime){String n=name==null?"":name.toLowerCase(Locale.ROOT);return mime!=null&&mime.startsWith("image/")||n.matches(".*\\.(jpg|jpeg|cr2|cr3|raw|dng|nef|nrw|arw|raf|rw2|orf|pef|srw|tif|tiff|heic|heif|png)$");}
 static String sha256Text(String value)throws Exception{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v));return b.toString();}
 /** Barra fixa abaixo do topo durante a importação por pasta: progresso + botão Cancelar. */
 void buildImportBar(LinearLayout root){
  importBar=row();importBar.setPadding(dp(14),dp(6),dp(6),dp(6));importBar.setBackground(LumoTheme.card(CARD,dp(16),dp(1)));
  LinearLayout info=vertical();
  importBarText=text("Lendo cartão USB…",13,TEXT,true);importBarText.setSingleLine(true);importBarText.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(importBarText);
  importBarProgress=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);importBarProgress.setIndeterminate(true);importBarProgress.setProgressTintList(ColorStateList.valueOf(CYAN));importBarProgress.setIndeterminateTintList(ColorStateList.valueOf(CYAN));
  LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(6));pp.topMargin=dp(6);info.addView(importBarProgress,pp);
  importBar.addView(info,new LinearLayout.LayoutParams(0,-2,1));
  importCancelButton=compactButton("Cancelar",v->{haptic(v);confirmCancelImport();});importCancelButton.setTextColor(DANGER);leadingIcon(importCancelButton,"close",DANGER);importCancelButton.setContentDescription("Cancelar importação da pasta");
  LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=dp(10);importBar.addView(importCancelButton,cp);
  importBar.setVisibility(View.GONE);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.leftMargin=dp(14);lp.rightMargin=dp(14);lp.bottomMargin=dp(6);root.addView(importBar,lp);
 }
 void updateImportBar(){if(importBar==null)return;
  boolean show=importingEvent;importBar.setVisibility(show?View.VISIBLE:View.GONE);if(!show)return;
  boolean cancelling=importCancelRequested;int total=importTotal,processed=importProcessed;
  String label=cancelling?"Cancelando importação…":importScanning?"Lendo cartão USB… "+importFound+(importFound==1?" foto encontrada":" fotos encontradas"):"Importando do cartão · "+Math.max(1,Math.min(processed,total))+" de "+total;
  if(!label.contentEquals(importBarText.getText()))importBarText.setText(label);
  boolean indeterminate=importScanning||cancelling||total<=0;importBarProgress.setIndeterminate(indeterminate);if(!indeterminate){importBarProgress.setMax(total);importBarProgress.setProgress(processed);}
  importCancelButton.setEnabled(!cancelling);importCancelButton.setAlpha(cancelling?.45f:1f);importCancelButton.setText(cancelling?"Cancelando…":"Cancelar");
 }
 void confirmCancelImport(){if(!importingEvent||importCancelRequested)return;
  new AlertDialog.Builder(MainActivity.this).setTitle("Cancelar importação?").setMessage("As fotos já copiadas continuam no evento e seguem para a edição. As demais da pasta não serão importadas — você pode importar a pasta de novo depois; as já trazidas são reconhecidas como duplicadas.")
   .setPositiveButton("Cancelar importação",(d,n)->{if(!importingEvent)return;importCancelRequested=true;updateImportBar();})
   .setNegativeButton("Continuar importando",null).show();
 }
 void importCard(Uri tree){if(!requireEvent()||importingEvent)return;if(deletingPhotos){message("Aguarde a exclusão terminar.");return;}
  final String importEvent=database.activeEvent();
  final String settings;try{JSONObject cfg=buildCurrentSettings();settings=cfg.toString();getSharedPreferences("hisho",0).edit().putString("liveSettings",settings).apply();}catch(Exception e){message("Não foi possível preparar as configurações de edição.");return;}
  final boolean ownService=CaptureService.active==null;
  if(ownService){try{startForegroundService(new Intent(this,CaptureService.class).setAction("queue_import").putExtra("settings",settings));}catch(Exception e){message("Não foi possível iniciar a fila de edição: "+e.getMessage());return;}}
  importCancelRequested=false;importFound=0;importProcessed=0;importTotal=0;importScanning=true;importingEvent=true;
  updateImportBar();
  images.execute(()->{
   int imported=0,duplicates=0,unsupported=0,failed=0;ArrayList<CardPhoto> photos;
   try{String rootId=DocumentsContract.getTreeDocumentId(tree);Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,rootId);photos=scanCardFolder(tree,root,0,new HashSet<>());}
   catch(CancellationException e){photos=new ArrayList<>();}
   catch(Exception e){photos=new ArrayList<>();failed++;final String error=e.getMessage();handler.post(()->message("Não foi possível ler o cartão: "+error));}
   importTotal=photos.size();importProcessed=0;importScanning=false;handler.post(this::updateImportBar);
   File spool=new File(getFilesDir(),"queue");if(!spool.exists())spool.mkdirs();
   for(CardPhoto photo:photos){if(importCancelRequested||!DemoLicense.allowed(MainActivity.this))break;importProcessed++;File temp=null;String insertedId="";try{
    String lower=photo.name.toLowerCase(Locale.ROOT);boolean raw=RawPreview.isRaw(lower);if(!(lower.endsWith(".jpg")||lower.endsWith(".jpeg")||raw)){unsupported++;continue;}
    long size=photo.size;long modified=photo.modified;String sourceKey=photo.uri.toString()+"|"+size+"|"+modified;String id=sha256Text("card:"+importEvent+":"+sourceKey);if(database.exists(id)){duplicates++;continue;}
    if(getFilesDir().getUsableSpace()<(size>0?size*3L:64L*1024L*1024L))throw new IOException("Pouco espaço livre no aparelho.");
    temp=new File(spool,id+".jpg");try(InputStream in=getContentResolver().openInputStream(photo.uri);OutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("Foto não disponível no cartão.");byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1){if(importCancelRequested)throw new CancellationException();out.write(buf,0,n);}}
    String hash=PhotoHash.sha256(temp);if(!database.duplicateByContent(hash).isEmpty()){temp.delete();duplicates++;continue;}
    String pHash="";try{pHash=PhotoHash.perceptualHash(temp);}catch(Exception ignored){}
    String base=photo.name.replaceFirst("\\.[^.]+$","").replaceAll("[^a-zA-Z0-9_-]","_");String outName=base+"_Cartao_"+id.substring(0,10)+".jpg";long captured=modified>0?modified:System.currentTimeMillis();
    database.addImported(id,temp,outName,settings,captured,hash,pHash,temp.length());insertedId=id;
    database.setAlbum(id,photo.album,photo.name);
    if(raw){String rawName=photo.name.replaceAll("[^a-zA-Z0-9_.-]","_");rawName=rawName.replaceFirst("\\.[^.]+$","")+"_"+id.substring(0,10)+photo.name.substring(photo.name.lastIndexOf('.'));Uri rawUri=database.save(temp,"RAW",rawName,id,"raw_original");File jpeg=new File(spool,id+"_preview.jpg");if(!RawPreview.extract(temp,jpeg)){database.set(id,"rawOnly","original",rawUri.toString());database.history(id,System.currentTimeMillis(),"RAW preservado","Sem prévia JPEG compatível; revelação RAW ainda não disponível.");temp.delete();imported++;continue;}temp.delete();if(!jpeg.renameTo(temp))throw new IOException("Falha ao preparar prévia RAW.");database.history(id,System.currentTimeMillis(),"RAW preservado","Edição utiliza a prévia JPEG embutida.");}
    Uri original=database.save(temp,"Originais",outName,id,"original");database.set(id,"ready","original",original.toString());imported++;
   }catch(CancellationException e){if(temp!=null)temp.delete();importProcessed--;break;}
   catch(Exception e){if(temp!=null)temp.delete();if(!insertedId.isEmpty())try{database.set(insertedId,"originalError","error",e.toString());}catch(Exception ignored){}failed++;}}
   if(ownService)try{startService(new Intent(MainActivity.this,CaptureService.class).setAction("finish_import"));}catch(Exception ignored){}
   final int done=imported,dupes=duplicates,skip=unsupported,errors=failed;final boolean cancelled=importCancelRequested;final int left=Math.max(0,importTotal-importProcessed);
   importingEvent=false;importCancelRequested=false;importScanning=false;
   handler.post(()->{updateImportBar();gallerySignature="";filesSignature="";reviewSignature="";if(selected==1)refreshFiles(true);if(selected==2)refreshReview(true);refreshGallery();
    if(cancelled)message("Importação cancelada: "+done+" foto(s) já estão na edição"+(dupes>0?", "+dupes+" duplicada(s)":"")+(errors>0?", "+errors+" erro(s)":"")+". "+left+" foto(s) da pasta não foram importadas.");
    else message("Cartão processado: "+done+" foto(s) na edição, "+dupes+" duplicada(s), "+skip+" arquivo(s) não compatível(is) e "+errors+" erro(s).");});
  });
 }
 static String read(InputStream stream)throws IOException{try(InputStream s=stream;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] a=new byte[8192];int n;while((n=s.read(a))!=-1){if(b.size()+n>1024*1024)throw new IOException("Arquivo maior que 1 MB.");b.write(a,0,n);}return b.toString("UTF-8");}}

 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==REQUEST_VIEWER){gallerySignature="";filesSignature="";reviewSignature="";requestedUri="";thumbCache.evictAll();refreshGallery();refreshFiles(true);refreshReview(true);return;}
  if(request==41&&result==RESULT_OK&&data!=null){final Uri dest=data.getData();images.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException("Destino indisponível");out.write(database.onlineManifest().getBytes("UTF-8"));handler.post(()->message("Manifesto exportado. Não inclui arquivos de foto nem credenciais."));}catch(Exception e){handler.post(()->message("Falha ao exportar manifesto: "+e.getMessage()));}});return;}
  if(request==REQUEST_CARD_FOLDER){if(result==RESULT_OK&&data!=null&&data.getData()!=null){Uri tree=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(tree,flags);importCard(tree);}catch(Exception e){message("Não foi possível acessar o cartão USB: "+e.getMessage());}}return;}
  if(request==30){if(result==RESULT_OK){refreshAccount();getSharedPreferences("hisho",0).edit().putString("fottoLastStatus","Conta conectada. Carregando eventos…").apply();loadFottoGalleries();updateFottoStatus();}return;}
  if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(uri,flags);getSharedPreferences("hisho",0).edit().putString("exportTreeUri",uri.toString()).apply();updateExportFolder();message("Pasta de exportação salva.");}catch(Exception e){message("Não foi possível manter acesso à pasta: "+e.getMessage());}return;}
  if(request==10&&result==RESULT_OK&&data!=null)try{JSONObject parsed=PhotoEditor.parse(read(getContentResolver().openInputStream(data.getData())));presetData=parsed;presetName="Predefinição importada";try(Cursor c=getContentResolver().query(data.getData(),new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())presetName=c.getString(0).replaceFirst("(?i)\\.xmp$","");}getSharedPreferences("hisho",0).edit().putString("preset",parsed.toString()).putString("presetName",presetName).putBoolean("presetEnabled",true).apply();PresetLibrary.put(MainActivity.this,presetName,parsed);publishLiveSettings();refreshQuickControls();message("Predefinição "+presetName+" importada.");}catch(Exception e){message("Predefinição anterior mantida: "+e.getMessage());}
 }
 /** Nome/e-mail da conta Fotto para o menu (contas conectadas antes do login do Lumo ainda não têm). */
 void refreshAccount(){if(FottoApi.token(this).isEmpty())return;final Context app=getApplicationContext();Thread t=new Thread(()->{try{LumoSession.rememberProfile(app,FottoApi.validateSession(app));}catch(Exception ignored){}},"lumo-account");t.setDaemon(true);t.start();}
 void message(String s){Toast.makeText(MainActivity.this,s,Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();QueueRecovery.resume(MainActivity.this);gallerySignature="";filesSignature="";reviewSignature="";requestedUri="";thumbCache.evictAll();handler.removeCallbacks(update);handler.post(update);}
 @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
 @Override protected void onSaveInstanceState(Bundle state){state.putInt("page",selected);state.putBoolean("follow",gallery.follow);state.putString("photo",gallery.selected);super.onSaveInstanceState(state);}
 @Override protected void onDestroy(){destroyed=true;if(gated){thumbnails.shutdownNow();images.shutdownNow();scoring.shutdownNow();fottoNetwork.shutdownNow();galleryWorker.shutdownNow();super.onDestroy();return;}if(tourDialog!=null)tourDialog.dismiss();previewGeneration++;stripGeneration++;handler.removeCallbacksAndMessages(null);thumbnails.shutdownNow();images.shutdownNow();scoring.shutdownNow();fottoNetwork.shutdownNow();galleryWorker.shutdownNow();thumbCache.evictAll();if(previewBitmap!=null)previewBitmap.recycle();database.close();if(registered)unregisterReceiver(receiver);super.onDestroy();}
}
